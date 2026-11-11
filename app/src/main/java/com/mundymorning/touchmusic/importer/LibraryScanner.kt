package com.mundymorning.touchmusic.importer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.mundymorning.touchmusic.core.AudioFormats
import com.mundymorning.touchmusic.core.LibraryPathPlanner
import com.mundymorning.touchmusic.core.ResolvedMetadata
import com.mundymorning.touchmusic.data.ImportJobEntity
import com.mundymorning.touchmusic.data.JobStatus
import com.mundymorning.touchmusic.data.LibraryRepository
import com.mundymorning.touchmusic.data.ScanFolderEntity
import com.mundymorning.touchmusic.data.Storage
import com.mundymorning.touchmusic.data.TrackEdit
import com.mundymorning.touchmusic.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class ScanState(val running: Boolean = false, val message: String? = null)

/** Discovery and rescans that index files where they are (nothing is copied or moved). */
class LibraryScanner(
    private val context: Context,
    private val repo: LibraryRepository,
    private val onLibraryChanged: () -> Unit,
) {
    private val mediaStore = MediaStoreScanner(context, repo)
    val lister = DocumentLister(context)
    private val indexer = DocumentIndexer(context, repo)

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private suspend fun <T> busy(what: String, block: suspend () -> T): T? {
        if (_state.value.running) return null
        _state.value = ScanState(true, what)
        return try {
            block()
        } catch (e: SecurityException) {
            _state.value = ScanState(false, "Permission was denied or has been revoked")
            null
        } catch (e: Exception) {
            _state.value = ScanState(false, "Scan failed: ${e.message ?: e.javaClass.simpleName}")
            null
        } finally {
            if (_state.value.running) _state.value = _state.value.copy(running = false)
            onLibraryChanged()
        }
    }

    suspend fun scanPhone(downloadsOnly: Boolean) {
        val label = if (downloadsOnly) "Downloads scan" else "Phone scan"
        busy(if (downloadsOnly) "Scanning Downloads…" else "Scanning phone storage…") {
            val summary = mediaStore.scan(downloadsOnly)
            repo.cleanupOrphans()
            record(label, summary.describe())
            _state.value = ScanState(false, "$label: ${summary.describe()}")
        }
    }

    suspend fun listFolder(treeUri: Uri): FolderListing? = busy("Looking through the folder…") {
        val listing = lister.listFolder(treeUri)
        repo.db.folders().insert(ScanFolderEntity(treeUri = treeUri.toString(), name = listing.name, addedAt = System.currentTimeMillis()))
        _state.value = ScanState(false, null)
        listing
    }

    /** Re-checks everything already known: MediaStore (if permitted) and every saved folder. */
    suspend fun rescanAll(canReadMediaStore: Boolean) {
        busy("Rescanning library…") {
            val parts = ArrayList<String>()
            if (canReadMediaStore) parts.add("Phone: " + mediaStore.scan(false).describe())
            for (folder in repo.db.folders().list()) {
                _state.value = ScanState(true, "Rescanning ${folder.name}…")
                parts.add("${folder.name}: " + rescanFolder(folder))
            }
            repo.cleanupOrphans()
            val text = if (parts.isEmpty()) "Nothing to rescan yet. Scan the phone or choose a folder first." else parts.joinToString("\n")
            if (parts.isNotEmpty()) record("Library rescan", text)
            _state.value = ScanState(false, text)
        }
    }

    private suspend fun rescanFolder(folder: ScanFolderEntity): String = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(folder.treeUri)
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        if (!granted) return@withContext "access to this folder was lost; choose it again"
        val listing = lister.listFolder(treeUri)
        val known = repo.tracks.inTree(folder.treeUri)
        // Only folders that were indexed in place take part in rescans.
        if (known.isEmpty()) return@withContext "${listing.audio.size} audio files, ${listing.zips.size} ZIPs (not indexed in place)"
        var added = 0
        var relinked = 0
        var failed = 0
        val present = HashSet<String>()
        for (spec in listing.audio) {
            present.add(spec.uri)
            when (indexer.index(spec, null)) {
                IndexOutcome.ADDED, IndexOutcome.ADDED_NEEDS_REVIEW -> added++
                IndexOutcome.RELINKED -> relinked++
                IndexOutcome.UNPLAYABLE -> failed++
                else -> Unit
            }
        }
        var missing = 0
        for (t in known) {
            if (t.uri !in present && !t.missing) {
                // A renamed/moved file was re-linked above and now carries its new URI.
                val current = repo.tracks.byId(t.id)
                if (current != null && current.uri == t.uri) {
                    repo.tracks.setMissing(t.id, true)
                    missing++
                }
            }
        }
        repo.db.folders().touch(folder.treeUri, System.currentTimeMillis())
        buildString {
            append("$added new")
            if (relinked > 0) append(", $relinked re-linked")
            if (missing > 0) append(", $missing no longer available")
            if (failed > 0) append(", $failed unreadable")
        }
    }

    suspend fun forgetFolder(folder: ScanFolderEntity) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(folder.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        repo.db.folders().delete(folder.treeUri)
    }

    private suspend fun record(label: String, summary: String) {
        val now = System.currentTimeMillis()
        repo.db.imports().insertJob(
            ImportJobEntity(label = label, createdAt = now, finishedAt = now, status = JobStatus.COMPLETED, summary = summary),
        )
    }

    /**
     * Saves a manual correction. Files that this app placed in the organised library are also
     * moved to the matching Music/Artist/Album folder; files indexed in place are never touched.
     * Tags inside the audio file itself are not rewritten.
     */
    suspend fun applyEdit(track: TrackEntity, edit: TrackEdit) = withContext(Dispatchers.IO) {
        val updated = repo.editTrack(track.id, edit) ?: return@withContext
        if (updated.storage != Storage.LIBRARY) return@withContext
        val meta = ResolvedMetadata(
            title = updated.title, artist = updated.artist, albumArtist = updated.albumArtist, album = updated.album,
            trackNumber = updated.trackNumber, discNumber = updated.discNumber, genre = updated.genre, year = updated.year,
            durationMs = updated.durationMs, needsReview = false, reviewReason = null, inferredFromPath = false,
        )
        val ext = AudioFormats.extensionOf(updated.displayName)
        val placement = LibraryPathPlanner.plan(meta, ext, updated.displayName)
        if (placement.directory == updated.folder && placement.fileName == updated.displayName) return@withContext
        try {
            val uri = Uri.parse(updated.uri)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.RELATIVE_PATH, placement.directory + "/")
                put(MediaStore.Audio.Media.DISPLAY_NAME, placement.fileName)
            }
            if (context.contentResolver.update(uri, values, null, null) > 0) {
                var name = placement.fileName
                var folder = placement.directory
                context.contentResolver.query(
                    uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        c.getString(1)?.let { folder = it.trimEnd('/') }
                    }
                }
                repo.tracks.update(
                    updated.copy(displayName = name, folder = folder, pathKey = AndroidImportEnvironment.pathKey("primary", "$folder/$name")),
                )
            }
        } catch (e: Exception) {
            // The file could not be moved (e.g. no longer owned after a reinstall); the
            // corrected metadata is still saved in the library.
        }
    }
}
