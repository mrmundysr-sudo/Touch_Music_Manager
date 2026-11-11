package com.mundymorning.touchmusic.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a track's file lives and who manages it. */
object Storage {
    /** Copied into the organised Music/Artist/Album library by this app (MediaStore URI). */
    const val LIBRARY = "LIBRARY"
    /** Found on the device through MediaStore and left where it is. */
    const val MEDIASTORE = "MEDIASTORE"
    /** Inside a user-selected folder (Storage Access Framework URI) and left where it is. */
    const val DOCUMENT = "DOCUMENT"
}

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["uri"], unique = true),
        Index("sha256"),
        Index("albumId"),
        Index("artistId"),
        Index("sizeBytes"),
        Index("pathKey"),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable playback URI (content://…). Unique: rescans update rows instead of adding new ones. */
    val uri: String,
    val storage: String,
    /** volume:relative/path, lower-case; identifies the same physical file across URI schemes. */
    val pathKey: String?,
    /** Tree URI of the user-selected folder this track was found in, if any. */
    val treeUri: String?,
    val displayName: String,
    val folder: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val albumId: Long,
    val artistId: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val genre: String?,
    val year: Int?,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    /** Content hash; always present for files this app copied, computed lazily for others. */
    val sha256: String?,
    val dateAdded: Long,
    val dateModified: Long,
    val needsReview: Boolean,
    val reviewReason: String?,
    val favorite: Boolean = false,
    /** The file could not be found at the last rescan (deleted, moved or storage disconnected). */
    val missing: Boolean = false,
    /** Metadata was corrected by hand; rescans must not overwrite it. */
    val userEdited: Boolean = false,
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
    val importJobId: Long? = null,
)

@Entity(tableName = "albums", indices = [Index(value = ["titleKey", "artistId"], unique = true), Index("artistId")])
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val titleKey: String,
    val artistId: Long,
    val artistName: String,
    val year: Int?,
)

@Entity(tableName = "artists", indices = [Index(value = ["nameKey"], unique = true)])
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameKey: String,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)

object JobStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS"
    const val CANCELLED = "CANCELLED"
}

object ItemStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

object ItemKind {
    const val ZIP = "ZIP"
    const val AUDIO = "AUDIO"
}

/** What to do with loose audio files (ZIP contents are always extracted into the library). */
object AudioMode {
    const val INDEX = "INDEX"
    const val COPY = "COPY"
    const val MOVE = "MOVE"
}

@Entity(tableName = "import_jobs")
data class ImportJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val createdAt: Long,
    val finishedAt: Long? = null,
    val status: String = JobStatus.PENDING,
    val audioMode: String = AudioMode.INDEX,
    val deleteSourceZips: Boolean = false,
    val summary: String? = null,
)

@Entity(
    tableName = "import_items",
    foreignKeys = [ForeignKey(entity = ImportJobEntity::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("jobId"), Index("status")],
)
data class ImportItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val uri: String,
    val displayName: String,
    val kind: String,
    /** Folder-relative path used as a metadata clue. */
    val pathHint: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val treeUri: String?,
    val status: String = ItemStatus.PENDING,
    val imported: Int = 0,
    val needsReview: Int = 0,
    val duplicates: Int = 0,
    val unsupported: Int = 0,
    val ignored: Int = 0,
    val failed: Int = 0,
    val message: String? = null,
)

/** One skipped or failed file, kept so the user can see exactly what happened. */
@Entity(
    tableName = "import_logs",
    foreignKeys = [ForeignKey(entity = ImportJobEntity::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("jobId")],
)
data class ImportLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val itemId: Long,
    val path: String,
    val status: String,
    val message: String?,
    val time: Long,
)

/** A folder the user granted access to; rescanned on demand. */
@Entity(tableName = "scan_folders")
data class ScanFolderEntity(
    @PrimaryKey val treeUri: String,
    val name: String,
    val addedAt: Long,
    val lastScanAt: Long? = null,
)
