package com.mundymorning.touchmusic.data

import androidx.room.withTransaction
import com.mundymorning.touchmusic.core.ResolvedMetadata
import java.util.Locale

data class NewTrack(
    val uri: String,
    val storage: String,
    val pathKey: String?,
    val treeUri: String?,
    val displayName: String,
    val folder: String,
    val meta: ResolvedMetadata,
    val sizeBytes: Long,
    val mimeType: String?,
    val sha256: String?,
    val dateModified: Long,
    val importJobId: Long? = null,
)

data class TrackEdit(
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val genre: String?,
)

/** Single entry point for library writes; keeps artist/album relationships consistent. */
class LibraryRepository(val db: AppDatabase) {
    val tracks get() = db.tracks()
    val albums get() = db.albums()
    val artists get() = db.artists()
    val playlists get() = db.playlists()

    private fun key(s: String) = s.trim().lowercase(Locale.ROOT)

    private suspend fun artistId(name: String): Long {
        val k = key(name)
        return db.artists().find(k)?.id ?: db.artists().insert(ArtistEntity(name = name.trim(), nameKey = k))
    }

    private suspend fun albumId(title: String, artistName: String, artistId: Long, year: Int?): Long {
        val k = key(title)
        return db.albums().find(k, artistId)?.id
            ?: db.albums().insert(AlbumEntity(title = title.trim(), titleKey = k, artistId = artistId, artistName = artistName.trim(), year = year))
    }

    suspend fun addTrack(t: NewTrack): Long = db.withTransaction {
        val artistId = artistId(t.meta.albumArtist)
        val albumId = albumId(t.meta.album, t.meta.albumArtist, artistId, t.meta.year)
        db.tracks().insert(
            TrackEntity(
                uri = t.uri,
                storage = t.storage,
                pathKey = t.pathKey,
                treeUri = t.treeUri,
                displayName = t.displayName,
                folder = t.folder,
                title = t.meta.title,
                artist = t.meta.artist,
                albumArtist = t.meta.albumArtist,
                album = t.meta.album,
                albumId = albumId,
                artistId = artistId,
                trackNumber = t.meta.trackNumber,
                discNumber = t.meta.discNumber,
                genre = t.meta.genre,
                year = t.meta.year,
                durationMs = t.meta.durationMs,
                sizeBytes = t.sizeBytes,
                mimeType = t.mimeType,
                sha256 = t.sha256,
                dateAdded = System.currentTimeMillis(),
                dateModified = t.dateModified,
                needsReview = t.meta.needsReview,
                reviewReason = t.meta.reviewReason,
                importJobId = t.importJobId,
            ),
        )
    }

    /** Refreshes a row from re-read metadata after the underlying file changed. */
    suspend fun refreshTrack(existing: TrackEntity, meta: ResolvedMetadata, sizeBytes: Long, dateModified: Long) = db.withTransaction {
        val artistId = artistId(meta.albumArtist)
        val albumId = albumId(meta.album, meta.albumArtist, artistId, meta.year)
        db.tracks().update(
            existing.copy(
                title = meta.title, artist = meta.artist, albumArtist = meta.albumArtist, album = meta.album,
                albumId = albumId, artistId = artistId, trackNumber = meta.trackNumber, discNumber = meta.discNumber,
                genre = meta.genre, year = meta.year, durationMs = meta.durationMs, sizeBytes = sizeBytes,
                dateModified = dateModified, needsReview = meta.needsReview, reviewReason = meta.reviewReason,
                missing = false, sha256 = null,
            ),
        )
    }

    /** Manual correction. Clears the review flag and protects the row from later rescans. */
    suspend fun editTrack(id: Long, edit: TrackEdit): TrackEntity? = db.withTransaction {
        val existing = db.tracks().byId(id) ?: return@withTransaction null
        val albumArtist = edit.albumArtist.ifBlank { edit.artist }
        val artistId = artistId(albumArtist)
        val albumId = albumId(edit.album, albumArtist, artistId, edit.year)
        val updated = existing.copy(
            title = edit.title.trim(), artist = edit.artist.trim(), albumArtist = albumArtist.trim(), album = edit.album.trim(),
            albumId = albumId, artistId = artistId, trackNumber = edit.trackNumber, discNumber = edit.discNumber,
            year = edit.year, genre = edit.genre?.trim()?.ifEmpty { null },
            needsReview = false, reviewReason = null, userEdited = true,
        )
        db.tracks().update(updated)
        db.albums().deleteOrphans()
        db.artists().deleteOrphans()
        updated
    }

    suspend fun removeTrack(id: Long) = db.withTransaction {
        db.tracks().delete(id)
        db.albums().deleteOrphans()
        db.artists().deleteOrphans()
    }

    suspend fun removeMissing(): Int = db.withTransaction {
        val n = db.tracks().deleteMissing()
        db.albums().deleteOrphans()
        db.artists().deleteOrphans()
        n
    }

    suspend fun cleanupOrphans() = db.withTransaction {
        db.albums().deleteOrphans()
        db.artists().deleteOrphans()
    }

    suspend fun createPlaylist(name: String): Long =
        db.playlists().insert(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))

    /** Returns false when the track was already in the playlist. */
    suspend fun addToPlaylist(playlistId: Long, trackId: Long): Boolean = db.withTransaction {
        if (db.playlists().contains(playlistId, trackId) > 0) return@withTransaction false
        val pos = db.playlists().maxPosition(playlistId) + 1
        db.playlists().addTrack(PlaylistTrackEntity(playlistId = playlistId, trackId = trackId, position = pos))
        true
    }
}
