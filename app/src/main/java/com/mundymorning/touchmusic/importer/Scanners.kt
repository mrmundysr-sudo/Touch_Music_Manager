package com.mundymorning.touchmusic.importer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.mundymorning.touchmusic.core.AudioFormats
import com.mundymorning.touchmusic.core.MetadataResolver
import com.mundymorning.touchmusic.core.RawTags
import com.mundymorning.touchmusic.data.ItemKind
import com.mundymorning.touchmusic.data.LibraryRepository
import com.mundymorning.touchmusic.data.NewTrack
import com.mundymorning.touchmusic.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file the user pointed at (directly or inside a chosen folder) that can be imported. */
data class SourceSpec(
    val uri: String,
    val name: String,
    val kind: String,
    val pathHint: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val treeUri: String?,
)

data class FolderListing(val treeUri: String, val name: String, val audio: List<SourceSpec>, val zips: List<SourceSpec>, val otherFiles: Int)

data class ScanSummary(val found: Int, val added: Int, val updated: Int, val relinked: Int, val missing: Int) {
    fun describe(): String = buildString {
        append("$found songs found, $added new")
        if (updated > 0) append(", $updated updated")
        if (relinked > 0) append(", $relinked re-linked")
        if (missing > 0) append(", $missing no longer available")
    }
}

/** Indexes audio that Android already knows about (MediaStore). Files stay where they are. */
class MediaStoreScanner(private val context: Context, private val repo: LibraryRepository) {
    private class Row(
        val id: Long, val name: String, val title: String?, val artist: String?, val album: String?, val albumArtist: String?,
        val track: Int, val year: Int, val duration: Long, val size: Long, val mime: String?, val modified: Long,
        val relativePath: String, val volume: String, val genre: String?, val disc: Int?, val compilation: Boolean,
    )

    suspend fun scan(downloadsOnly: Boolean): ScanSummary = withContext(Dispatchers.IO) {
        val rows = query(downloadsOnly)
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val seen = HashSet<Long>()
        var added = 0
        var updated = 0
        var relinked = 0
        val dao = repo.tracks
        for (chunk in rows.chunked(200)) {
            repo.db.withTransaction {
                for (r in chunk) {
                    val uri = ContentUris.withAppendedId(collection, r.id).toString()
                    val volume = if (r.volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "primary" else r.volume
                    val pathKey = AndroidImportEnvironment.pathKey(volume, r.relativePath + r.name)
                    val hint = r.relativePath + r.name
                    val tagTitle = r.title?.takeUnless { it == AudioFormats.stripExtension(r.name) }
                    val tags = RawTags(
                        title = tagTitle, artist = r.artist, albumArtist = r.albumArtist, album = r.album,
                        trackNumber = (r.track % 1000).takeIf { it > 0 },
                        discNumber = r.disc ?: (r.track / 1000).takeIf { it > 0 },
                        genre = r.genre, year = r.year.takeIf { it > 0 }, durationMs = r.duration, compilation = r.compilation,
                    )
                    val existing = dao.byUri(uri) ?: dao.byPathKey(pathKey)
                    if (existing != null) {
                        seen.add(existing.id)
                        if (!existing.userEdited && existing.storage == Storage.MEDIASTORE && existing.dateModified != r.modified) {
                            repo.refreshTrack(existing, MetadataResolver.resolve(tags, hint), r.size, r.modified)
                            updated++
                        } else if (existing.missing) {
                            dao.setMissing(existing.id, false)
                        }
                        continue
                    }
                    val moved = dao.missingMatch(r.size, r.name)
                    if (moved != null) {
                        // Same file under a new location: keep the row so playlists and favourites survive.
                        dao.update(moved.copy(uri = uri, pathKey = pathKey, folder = r.relativePath.trimEnd('/'), missing = false, storage = Storage.MEDIASTORE, treeUri = null))
                        seen.add(moved.id)
                        relinked++
                        continue
                    }
                    val id = repo.addTrack(
                        NewTrack(
                            uri = uri, storage = Storage.MEDIASTORE, pathKey = pathKey, treeUri = null, displayName = r.name,
                            folder = r.relativePath.trimEnd('/'), meta = MetadataResolver.resolve(tags, hint), sizeBytes = r.size,
                            mimeType = r.mime, sha256 = null, dateModified = r.modified,
                        ),
                    )
                    seen.add(id)
                    added++
                }
            }
        }
        var missing = 0
        if (!downloadsOnly) {
            for (t in dao.mediaStoreBacked()) {
                if (t.id !in seen && !t.missing) {
                    dao.setMissing(t.id, true)
                    missing++
                }
            }
        }
        ScanSummary(rows.size, added, updated, relinked, missing)
    }

    private fun query(downloadsOnly: Boolean): List<Row> = try {
        query(downloadsOnly, Build.VERSION.SDK_INT >= 30)
    } catch (e: IllegalArgumentException) {
        // A device without the newer columns: fall back to the basic set.
        query(downloadsOnly, false)
    }

    private fun query(downloadsOnly: Boolean, modern: Boolean): List<Row> {
        val projection = ArrayList<String>().apply {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.RELATIVE_PATH)
            add(MediaStore.Audio.Media.VOLUME_NAME)
            if (modern) {
                add("album_artist")
                add("genre")
                add("disc_number")
                add("compilation")
            }
        }
        var selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        var args: Array<String>? = null
        if (downloadsOnly) {
            selection += " AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("Download/%")
        }
        val out = ArrayList<Row>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection.toTypedArray(), selection, args, null,
        )?.use { c ->
            fun col(name: String) = c.getColumnIndex(name)
            val iId = col(MediaStore.Audio.Media._ID)
            val iName = col(MediaStore.Audio.Media.DISPLAY_NAME)
            val iTitle = col(MediaStore.Audio.Media.TITLE)
            val iArtist = col(MediaStore.Audio.Media.ARTIST)
            val iAlbum = col(MediaStore.Audio.Media.ALBUM)
            val iTrack = col(MediaStore.Audio.Media.TRACK)
            val iYear = col(MediaStore.Audio.Media.YEAR)
            val iDuration = col(MediaStore.Audio.Media.DURATION)
            val iSize = col(MediaStore.Audio.Media.SIZE)
            val iMime = col(MediaStore.Audio.Media.MIME_TYPE)
            val iModified = col(MediaStore.Audio.Media.DATE_MODIFIED)
            val iPath = col(MediaStore.Audio.Media.RELATIVE_PATH)
            val iVolume = col(MediaStore.Audio.Media.VOLUME_NAME)
            val iAlbumArtist = col("album_artist")
            val iGenre = col("genre")
            val iDisc = col("disc_number")
            val iCompilation = col("compilation")
            fun str(i: Int): String? = if (i >= 0 && !c.isNull(i)) c.getString(i) else null
            fun long(i: Int): Long = if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
            while (c.moveToNext()) {
                val name = str(iName) ?: continue
                val path = str(iPath) ?: ""
                if (!AudioFormats.isSupportedAudio(AudioFormats.extensionOf(name))) continue
                if (path.contains("Voice Notes", ignoreCase = true)) continue
                out.add(
                    Row(
                        id = long(iId), name = name, title = str(iTitle), artist = str(iArtist), album = str(iAlbum),
                        albumArtist = str(iAlbumArtist), track = long(iTrack).toInt(), year = long(iYear).toInt(),
                        duration = long(iDuration), size = long(iSize), mime = str(iMime), modified = long(iModified),
                        relativePath = path, volume = str(iVolume) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY, genre = str(iGenre),
                        disc = TagReader.leadingInt(str(iDisc)), compilation = str(iCompilation)?.trim() == "1",
                    ),
                )
            }
        }
        return out
    }
}

/** Lists audio files and ZIP archives in folders or picker selections the user authorised. */
class DocumentLister(private val context: Context) {
    private val resolver = context.contentResolver

    suspend fun listFolder(treeUri: Uri): FolderListing = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryName(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
            ?: rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "Folder" }
        val audio = ArrayList<SourceSpec>()
        val zips = ArrayList<SourceSpec>()
        var other = 0
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootId to rootName)
        val visited = HashSet<String>()
        while (queue.isNotEmpty()) {
            val (docId, path) = queue.removeFirst()
            if (!visited.add(docId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            try {
                resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = if (c.isNull(2)) null else c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.startsWith(".")) queue.add(id to "$path/$name")
                            continue
                        }
                        val spec = SourceSpec(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                            name = name,
                            kind = ItemKind.AUDIO,
                            pathHint = "$path/$name",
                            sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                            lastModified = if (c.isNull(4)) 0L else c.getLong(4) / 1000,
                            treeUri = treeUri.toString(),
                        )
                        val ext = AudioFormats.extensionOf(name)
                        when {
                            name.startsWith("._") -> other++
                            AudioFormats.isSupportedAudio(ext) -> audio.add(spec)
                            AudioFormats.isZip(ext) -> zips.add(spec.copy(kind = ItemKind.ZIP))
                            else -> other++
                        }
                    }
                }
            } catch (e: Exception) {
                // An unreadable sub-folder should not abort the whole scan.
            }
        }
        FolderListing(treeUri.toString(), rootName, audio, zips, other)
    }

    /** Describes files returned by the system file picker. */
    suspend fun describe(uris: List<Uri>): List<SourceSpec> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            var name: String? = null
            var size = 0L
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = if (c.isNull(0)) null else c.getString(0)
                        size = if (c.isNull(1)) 0L else c.getLong(1)
                    }
                }
            } catch (e: Exception) {
                // fall through with defaults
            }
            val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: return@mapNotNull null
            val ext = AudioFormats.extensionOf(finalName)
            val mime = try {
                resolver.getType(uri)
            } catch (e: Exception) {
                null
            }
            val kind = when {
                AudioFormats.isZip(ext) || mime == "application/zip" || mime == "application/x-zip-compressed" -> ItemKind.ZIP
                else -> ItemKind.AUDIO
            }
            SourceSpec(uri.toString(), finalName, kind, finalName, size, System.currentTimeMillis() / 1000, null)
        }
    }

    private fun queryName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}

enum class IndexOutcome { ADDED, ADDED_NEEDS_REVIEW, ALREADY_PRESENT, RELINKED, UNSUPPORTED, UNPLAYABLE }

/** Adds a document to the library without copying it ("leave originals in place"). */
class DocumentIndexer(private val context: Context, private val repo: LibraryRepository) {
    suspend fun index(spec: SourceSpec, jobId: Long?): IndexOutcome {
        val dao = repo.tracks
        val uri = Uri.parse(spec.uri)
        dao.byUri(spec.uri)?.let {
            if (it.missing) dao.setMissing(it.id, false)
            return IndexOutcome.ALREADY_PRESENT
        }
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            null
        }
        val pathKey = AndroidImportEnvironment.documentPathKey(docId, uri.authority)
        if (pathKey != null) {
            dao.byPathKey(pathKey)?.let {
                if (it.missing) dao.setMissing(it.id, false)
                return IndexOutcome.ALREADY_PRESENT
            }
        }
        val ext = AudioFormats.extensionOf(spec.name)
        if (!AudioFormats.isSupportedAudio(ext)) return IndexOutcome.UNSUPPORTED
        val folder = spec.pathHint.substringBeforeLast('/', "")
        dao.missingMatch(spec.sizeBytes, spec.name)?.let { moved ->
            dao.update(moved.copy(uri = spec.uri, pathKey = pathKey, treeUri = spec.treeUri, folder = folder, missing = false, storage = Storage.DOCUMENT))
            return IndexOutcome.RELINKED
        }
        val probe = TagReader.read { it.setDataSource(context, uri) }
        if (!probe.playable) return IndexOutcome.UNPLAYABLE
        val meta = MetadataResolver.resolve(probe.tags, spec.pathHint)
        repo.addTrack(
            NewTrack(
                uri = spec.uri, storage = Storage.DOCUMENT, pathKey = pathKey, treeUri = spec.treeUri, displayName = spec.name,
                folder = folder, meta = meta, sizeBytes = spec.sizeBytes, mimeType = AudioFormats.mimeTypeFor(ext),
                sha256 = null, dateModified = spec.lastModified, importJobId = jobId,
            ),
        )
        return if (meta.needsReview) IndexOutcome.ADDED_NEEDS_REVIEW else IndexOutcome.ADDED
    }
}
