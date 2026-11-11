package com.mundymorning.touchmusic.importer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.mundymorning.touchmusic.core.AudioFormats
import com.mundymorning.touchmusic.core.CommitRequest
import com.mundymorning.touchmusic.core.ImportEngine
import com.mundymorning.touchmusic.core.ImportEnvironment
import com.mundymorning.touchmusic.core.ProbeResult
import com.mundymorning.touchmusic.data.LibraryRepository
import com.mundymorning.touchmusic.data.NewTrack
import com.mundymorning.touchmusic.data.Storage
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Platform side of the import engine. The organised library lives in shared storage under
 * Music/Artist/Album and is written through MediaStore, which needs no storage permission,
 * survives uninstalling the app and makes the files visible to other players.
 */
class AndroidImportEnvironment(
    private val context: Context,
    private val repo: LibraryRepository,
    private val jobId: Long,
) : ImportEnvironment {
    private val resolver = context.contentResolver
    private val tempDir = tempDir(context)

    override fun availableBytes(): Long {
        val cache = tempDir.usableSpace
        val shared = try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (e: Exception) {
            cache
        }
        return minOf(cache, shared)
    }

    override fun createTempFile(extension: String): File = File(tempDir, "${UUID.randomUUID()}.$extension")

    override fun probe(file: File, extension: String): ProbeResult = TagReader.read { it.setDataSource(file.absolutePath) }

    override fun isDuplicate(sha256: String, sizeBytes: Long): Boolean = runBlocking {
        if (repo.tracks.bySha(sha256) != null) return@runBlocking true
        // Files that were indexed in place have no hash yet; hash same-sized candidates on demand.
        for (candidate in repo.tracks.unhashedBySize(sizeBytes)) {
            val hash = try {
                resolver.openInputStream(Uri.parse(candidate.uri))?.use { ImportEngine.sha256(it) }
            } catch (e: Exception) {
                null
            } ?: continue
            repo.tracks.setSha(candidate.id, hash)
            if (hash == sha256) return@runBlocking true
        }
        false
    }

    override fun commit(request: CommitRequest) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, request.placement.fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, AudioFormats.mimeTypeFor(request.extension))
            put(MediaStore.Audio.Media.RELATIVE_PATH, request.placement.directory + "/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // MediaStore never overwrites: a name clash gets a " (1)" style suffix.
        val pendingUri = resolver.insert(collection, values) ?: throw IOException("Media library refused the new file")
        try {
            resolver.openOutputStream(pendingUri)?.use { out ->
                FileInputStream(request.tempFile).use { it.copyTo(out, 64 * 1024) }
            } ?: throw IOException("Could not open the destination for writing")

            // Verify what actually landed on disk before making it visible.
            val written = resolver.openInputStream(pendingUri)?.use { ImportEngine.sha256(it) }
                ?: throw IOException("Could not re-read the copied file")
            if (written != request.sha256) throw IOException("Copied file failed verification")

            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(pendingUri, done, null, null)
        } catch (e: Exception) {
            try {
                resolver.delete(pendingUri, null, null)
            } catch (ignored: Exception) {
            }
            throw e
        }

        var displayName = request.placement.fileName
        var folder = request.placement.directory
        try {
            resolver.query(
                pendingUri,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { displayName = it }
                    c.getString(1)?.let { folder = it.trimEnd('/') }
                }
            }
        } catch (e: Exception) {
            // keep planned values
        }
        val id = ContentUris.parseId(pendingUri)
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        runBlocking {
            repo.addTrack(
                NewTrack(
                    uri = uri.toString(),
                    storage = Storage.LIBRARY,
                    pathKey = pathKey("primary", "$folder/$displayName"),
                    treeUri = null,
                    displayName = displayName,
                    folder = folder,
                    meta = request.metadata,
                    sizeBytes = request.sizeBytes,
                    mimeType = AudioFormats.mimeTypeFor(request.extension),
                    sha256 = request.sha256,
                    dateModified = System.currentTimeMillis() / 1000,
                    importJobId = jobId,
                ),
            )
        }
    }

    companion object {
        fun tempDir(context: Context): File = File(context.cacheDir, "import_tmp").apply { mkdirs() }

        fun clearTemp(context: Context) {
            tempDir(context).listFiles()?.forEach { it.delete() }
        }

        fun pathKey(volume: String, relativePath: String): String =
            (volume.lowercase(Locale.ROOT) + ":" + relativePath.trim('/')).lowercase(Locale.ROOT)

        /** Path key for documents served by the system external-storage provider. */
        fun documentPathKey(documentId: String?, authority: String?): String? {
            if (authority != "com.android.externalstorage.documents" || documentId == null) return null
            val idx = documentId.indexOf(':')
            if (idx <= 0) return null
            return pathKey(documentId.substring(0, idx), documentId.substring(idx + 1))
        }
    }
}
