package com.mundymorning.touchmusic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class IdUri(val id: Long, val uri: String, val missing: Boolean)

data class PlaylistSummary(val id: Long, val name: String, val createdAt: Long, val trackCount: Int)

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tracks WHERE missing = 1")
    suspend fun deleteMissing(): Int

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE pathKey = :pathKey LIMIT 1")
    suspend fun byPathKey(pathKey: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE sha256 = :sha AND missing = 0 LIMIT 1")
    suspend fun bySha(sha: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE sizeBytes = :size AND sha256 IS NULL AND missing = 0")
    suspend fun unhashedBySize(size: Long): List<TrackEntity>

    @Query("UPDATE tracks SET sha256 = :sha WHERE id = :id")
    suspend fun setSha(id: Long, sha: String)

    @Query("SELECT * FROM tracks WHERE missing = 1 AND sizeBytes = :size AND displayName = :name LIMIT 1")
    suspend fun missingMatch(size: Long, name: String): TrackEntity?

    @Query("SELECT id, uri, missing FROM tracks WHERE storage IN ('MEDIASTORE', 'LIBRARY')")
    suspend fun mediaStoreBacked(): List<IdUri>

    @Query("SELECT id, uri, missing FROM tracks WHERE treeUri = :treeUri")
    suspend fun inTree(treeUri: String): List<IdUri>

    @Query("UPDATE tracks SET missing = :missing WHERE id = :id")
    suspend fun setMissing(id: Long, missing: Boolean)

    @Query("UPDATE tracks SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE tracks SET lastPlayedAt = :time, playCount = playCount + 1 WHERE id = :id")
    suspend fun markPlayed(id: Long, time: Long)

    @Query("SELECT * FROM tracks WHERE missing = 0 ORDER BY title COLLATE NOCASE")
    fun songs(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE missing = 0 AND albumId = :albumId ORDER BY COALESCE(discNumber, 1), COALESCE(trackNumber, 9999), title COLLATE NOCASE")
    fun albumTracks(albumId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE missing = 0 AND artistId = :artistId ORDER BY album COLLATE NOCASE, COALESCE(discNumber, 1), COALESCE(trackNumber, 9999), title COLLATE NOCASE")
    fun artistTracks(artistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE missing = 0 ORDER BY dateAdded DESC, id DESC LIMIT :limit")
    fun recentlyAdded(limit: Int): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE missing = 0 AND lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun recentlyPlayed(limit: Int): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE missing = 0 AND favorite = 1 ORDER BY title COLLATE NOCASE")
    fun favorites(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE missing = 0 AND needsReview = 1 ORDER BY dateAdded DESC, title COLLATE NOCASE")
    fun needsReview(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE missing = 0 AND (title LIKE :q OR artist LIKE :q OR album LIKE :q) ORDER BY title COLLATE NOCASE LIMIT 100")
    fun search(q: String): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks WHERE missing = 0")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE missing = 1")
    fun missingCount(): Flow<Int>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId AND missing = 0 ORDER BY COALESCE(trackNumber, 9999) LIMIT 1")
    suspend fun firstOfAlbum(albumId: Long): TrackEntity?
}

@Dao
interface AlbumDao {
    @Insert
    suspend fun insert(album: AlbumEntity): Long

    @Query("SELECT * FROM albums WHERE titleKey = :titleKey AND artistId = :artistId LIMIT 1")
    suspend fun find(titleKey: String, artistId: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE id = :id")
    fun observe(id: Long): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE id IN (SELECT albumId FROM tracks WHERE missing = 0) ORDER BY title COLLATE NOCASE")
    fun all(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE artistId = :artistId AND id IN (SELECT albumId FROM tracks WHERE missing = 0) ORDER BY COALESCE(year, 9999), title COLLATE NOCASE")
    fun byArtist(artistId: Long): Flow<List<AlbumEntity>>

    @Query(
        "SELECT a.* FROM albums a JOIN tracks t ON t.albumId = a.id WHERE t.missing = 0 " +
            "GROUP BY a.id ORDER BY MAX(t.dateAdded) DESC LIMIT :limit",
    )
    fun recentlyAdded(limit: Int): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE title LIKE :q AND id IN (SELECT albumId FROM tracks WHERE missing = 0) ORDER BY title COLLATE NOCASE LIMIT 50")
    fun search(q: String): Flow<List<AlbumEntity>>

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT DISTINCT albumId FROM tracks)")
    suspend fun deleteOrphans()
}

@Dao
interface ArtistDao {
    @Insert
    suspend fun insert(artist: ArtistEntity): Long

    @Query("SELECT * FROM artists WHERE nameKey = :nameKey LIMIT 1")
    suspend fun find(nameKey: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE id = :id")
    fun observe(id: Long): Flow<ArtistEntity?>

    @Query("SELECT * FROM artists WHERE id IN (SELECT artistId FROM tracks WHERE missing = 0) ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE name LIKE :q AND id IN (SELECT artistId FROM tracks WHERE missing = 0) ORDER BY name COLLATE NOCASE LIMIT 50")
    fun search(q: String): Flow<List<ArtistEntity>>

    @Query("DELETE FROM artists WHERE id NOT IN (SELECT DISTINCT artistId FROM tracks)")
    suspend fun deleteOrphans()
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query(
        "SELECT p.id AS id, p.name AS name, p.createdAt AS createdAt, " +
            "(SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount " +
            "FROM playlists p ORDER BY p.name COLLATE NOCASE",
    )
    fun summaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT t.* FROM playlist_tracks p JOIN tracks t ON t.id = p.trackId WHERE p.playlistId = :playlistId ORDER BY p.position, p.id")
    fun tracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun contains(playlistId: Long, trackId: Long): Int

    @Insert
    suspend fun addTrack(entry: PlaylistTrackEntity): Long

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: Long)
}

@Dao
interface ImportDao {
    @Insert
    suspend fun insertJob(job: ImportJobEntity): Long

    @Insert
    suspend fun insertItems(items: List<ImportItemEntity>)

    @Insert
    suspend fun insertLog(log: ImportLogEntity): Long

    @Update
    suspend fun updateJob(job: ImportJobEntity)

    @Update
    suspend fun updateItem(item: ImportItemEntity)

    @Query("SELECT * FROM import_jobs WHERE id = :id")
    suspend fun job(id: Long): ImportJobEntity?

    @Query("SELECT * FROM import_jobs ORDER BY createdAt DESC, id DESC LIMIT 50")
    fun jobs(): Flow<List<ImportJobEntity>>

    @Query("SELECT * FROM import_jobs WHERE status IN ('PENDING', 'RUNNING') ORDER BY id")
    suspend fun unfinishedJobs(): List<ImportJobEntity>

    @Query("SELECT * FROM import_items WHERE jobId = :jobId ORDER BY id")
    suspend fun items(jobId: Long): List<ImportItemEntity>

    @Query("SELECT * FROM import_items WHERE jobId = :jobId ORDER BY id")
    fun observeItems(jobId: Long): Flow<List<ImportItemEntity>>

    @Query("UPDATE import_items SET status = 'PENDING' WHERE status = 'RUNNING'")
    suspend fun resetRunningItems()

    @Query("UPDATE import_items SET status = 'PENDING', message = NULL WHERE jobId = :jobId AND status IN ('FAILED', 'CANCELLED')")
    suspend fun requeueUnfinished(jobId: Long): Int

    @Query("UPDATE import_items SET status = 'CANCELLED' WHERE jobId = :jobId AND status IN ('PENDING', 'RUNNING')")
    suspend fun cancelPending(jobId: Long)

    @Query("SELECT * FROM import_logs WHERE jobId = :jobId ORDER BY id DESC LIMIT 500")
    fun logs(jobId: Long): Flow<List<ImportLogEntity>>

    @Query("DELETE FROM import_jobs WHERE id = :id")
    suspend fun deleteJob(id: Long)
}

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: ScanFolderEntity): Long

    @Query("UPDATE scan_folders SET lastScanAt = :time WHERE treeUri = :treeUri")
    suspend fun touch(treeUri: String, time: Long)

    @Query("DELETE FROM scan_folders WHERE treeUri = :treeUri")
    suspend fun delete(treeUri: String)

    @Query("SELECT * FROM scan_folders ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<ScanFolderEntity>>

    @Query("SELECT * FROM scan_folders")
    suspend fun list(): List<ScanFolderEntity>
}
