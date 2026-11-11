package com.mundymorning.touchmusic.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.mundymorning.touchmusic.core.EntryOutcome
import com.mundymorning.touchmusic.core.EntryStatus
import com.mundymorning.touchmusic.core.ImportEngine
import com.mundymorning.touchmusic.core.ImportLimits
import com.mundymorning.touchmusic.core.ImportListener
import com.mundymorning.touchmusic.core.SourceResult
import com.mundymorning.touchmusic.data.AudioMode
import com.mundymorning.touchmusic.data.ImportItemEntity
import com.mundymorning.touchmusic.data.ImportJobEntity
import com.mundymorning.touchmusic.data.ImportLogEntity
import com.mundymorning.touchmusic.data.ItemKind
import com.mundymorning.touchmusic.data.ItemStatus
import com.mundymorning.touchmusic.data.JobStatus
import com.mundymorning.touchmusic.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException

/** Live progress of the running import. Sources are archives or loose files; songs are what came out of them. */
data class ImportProgress(
    val jobId: Long,
    val label: String,
    val totalSources: Int,
    val completedSources: Int = 0,
    val failedSources: Int = 0,
    val currentSource: String? = null,
    val currentEntry: String? = null,
    val imported: Int = 0,
    val needsReview: Int = 0,
    val duplicates: Int = 0,
    val unsupported: Int = 0,
    val ignored: Int = 0,
    val failed: Int = 0,
    val cancelling: Boolean = false,
) {
    val finishedSources: Int get() = completedSources + failedSources
    val remainingSources: Int get() = (totalSources - finishedSources).coerceAtLeast(0)
    val skipped: Int get() = duplicates + unsupported
}

/**
 * Owns import jobs. Jobs and their items are persisted before any work starts, so an import
 * that is interrupted (app killed, reboot) can be picked up again: unfinished sources are simply
 * processed again from their beginning and songs that were already committed are skipped as
 * duplicates. Extraction is not resumed in the middle of an archive.
 */
class ImportController(
    private val context: Context,
    private val repo: LibraryRepository,
    private val onLibraryChanged: () -> Unit,
) {
    private val dao = repo.db.imports()
    private val indexer = DocumentIndexer(context, repo)

    private val _progress = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = _progress

    /** Summary of the most recently finished job, for a completion notification. */
    @Volatile var lastSummary: String? = null
        private set

    @Volatile private var cancelRequested = false
    @Volatile var running = false
        private set

    suspend fun enqueue(label: String, specs: List<SourceSpec>, audioMode: String, deleteSourceZips: Boolean): Long {
        val jobId = dao.insertJob(
            ImportJobEntity(label = label, createdAt = System.currentTimeMillis(), audioMode = audioMode, deleteSourceZips = deleteSourceZips),
        )
        specs.chunked(500).forEach { chunk ->
            dao.insertItems(
                chunk.map {
                    ImportItemEntity(
                        jobId = jobId, uri = it.uri, displayName = it.name, kind = it.kind, pathHint = it.pathHint,
                        sizeBytes = it.sizeBytes, lastModified = it.lastModified, treeUri = it.treeUri,
                    )
                },
            )
        }
        start()
        return jobId
    }

    fun start() {
        ContextCompat.startForegroundService(context, Intent(context, ImportService::class.java))
    }

    fun cancel() {
        cancelRequested = true
        _progress.value = _progress.value?.copy(cancelling = true)
    }

    /** Puts failed and cancelled sources of a job back in the queue. */
    suspend fun retry(jobId: Long) {
        val job = dao.job(jobId) ?: return
        if (dao.requeueUnfinished(jobId) > 0) {
            dao.updateJob(job.copy(status = JobStatus.PENDING, finishedAt = null))
            start()
        }
    }

    suspend fun hasUnfinishedJobs(): Boolean = dao.unfinishedJobs().isNotEmpty()

    /** Runs every unfinished job. Called by [ImportService]; returns when the queue is empty or cancelled. */
    suspend fun runAll() = withContext(Dispatchers.IO) {
        if (running) return@withContext
        running = true
        cancelRequested = false
        try {
            dao.resetRunningItems()
            AndroidImportEnvironment.clearTemp(context)
            while (true) {
                val job = dao.unfinishedJobs().firstOrNull() ?: break
                runJob(job)
                if (cancelRequested) {
                    for (other in dao.unfinishedJobs()) finishJob(other)
                    break
                }
            }
        } finally {
            AndroidImportEnvironment.clearTemp(context)
            running = false
            _progress.value = null
            onLibraryChanged()
        }
    }

    private suspend fun runJob(job: ImportJobEntity) {
        dao.updateJob(job.copy(status = JobStatus.RUNNING))
        val items = dao.items(job.id)
        var p = ImportProgress(
            jobId = job.id,
            label = job.label,
            totalSources = items.size,
            completedSources = items.count { it.status == ItemStatus.DONE },
            failedSources = items.count { it.status == ItemStatus.FAILED },
            imported = items.sumOf { it.imported },
            needsReview = items.sumOf { it.needsReview },
            duplicates = items.sumOf { it.duplicates },
            unsupported = items.sumOf { it.unsupported },
            ignored = items.sumOf { it.ignored },
            failed = items.sumOf { it.failed },
        )
        _progress.value = p
        val env = AndroidImportEnvironment(context, repo, job.id)

        for (item in items) {
            if (item.status != ItemStatus.PENDING) continue
            if (cancelRequested) break
            dao.updateItem(item.copy(status = ItemStatus.RUNNING))
            val base = p.copy(currentSource = item.displayName, currentEntry = null)
            _progress.value = base

            val live = SourceResult()
            val listener = object : ImportListener {
                override fun onEntry(outcome: EntryOutcome) {
                    when (outcome.status) {
                        EntryStatus.IMPORTED -> live.imported++
                        EntryStatus.IMPORTED_NEEDS_REVIEW -> {
                            live.imported++
                            live.needsReview++
                        }
                        EntryStatus.SKIPPED_DUPLICATE -> live.duplicates++
                        EntryStatus.SKIPPED_UNSUPPORTED -> live.unsupported++
                        EntryStatus.IGNORED_NOT_AUDIO -> live.ignored++
                        EntryStatus.FAILED -> live.failed++
                    }
                    if (outcome.status == EntryStatus.FAILED || outcome.status == EntryStatus.SKIPPED_DUPLICATE ||
                        outcome.status == EntryStatus.SKIPPED_UNSUPPORTED
                    ) {
                        runBlocking { log(job.id, item.id, outcome.path, outcome.status.name, outcome.message) }
                    }
                    _progress.value = base.plus(live).copy(currentEntry = outcome.path.substringAfterLast('/'), cancelling = cancelRequested)
                }

                override fun onProgress(currentPath: String, bytesProcessed: Long) {
                    _progress.value = base.plus(live).copy(currentEntry = currentPath.substringAfterLast('/'), cancelling = cancelRequested)
                }
            }
            val engine = ImportEngine(env, ImportLimits(), listener) { cancelRequested }
            val result = try {
                process(job, item, engine)
            } catch (e: Exception) {
                SourceResult().apply { fatalError = e.message ?: e.javaClass.simpleName }
            }

            val status = when {
                result.cancelled -> ItemStatus.CANCELLED
                result.fatalError != null -> ItemStatus.FAILED
                else -> ItemStatus.DONE
            }
            if (result.fatalError != null) log(job.id, item.id, item.displayName, "FAILED", result.fatalError)
            dao.updateItem(
                item.copy(
                    status = status,
                    imported = item.imported + result.imported,
                    needsReview = item.needsReview + result.needsReview,
                    duplicates = item.duplicates + result.duplicates,
                    unsupported = item.unsupported + result.unsupported,
                    ignored = item.ignored + result.ignored,
                    failed = item.failed + result.failed,
                    message = result.fatalError,
                ),
            )
            if (status == ItemStatus.DONE) afterSuccess(job, item, result)
            p = base.plus(result).copy(
                currentEntry = null,
                completedSources = base.completedSources + if (status == ItemStatus.DONE) 1 else 0,
                failedSources = base.failedSources + if (status == ItemStatus.FAILED) 1 else 0,
            )
            _progress.value = p
        }
        finishJob(job)
    }

    private fun ImportProgress.plus(r: SourceResult) = copy(
        imported = imported + r.imported,
        needsReview = needsReview + r.needsReview,
        duplicates = duplicates + r.duplicates,
        unsupported = unsupported + r.unsupported,
        ignored = ignored + r.ignored,
        failed = failed + r.failed,
    )

    private suspend fun process(job: ImportJobEntity, item: ImportItemEntity, engine: ImportEngine): SourceResult {
        val uri = Uri.parse(item.uri)
        fun open() = context.contentResolver.openInputStream(uri) ?: throw IOException("File could not be opened")
        if (item.kind == ItemKind.ZIP) return engine.importArchive(item.displayName) { open() }
        if (job.audioMode == AudioMode.INDEX) {
            val spec = SourceSpec(item.uri, item.displayName, item.kind, item.pathHint, item.sizeBytes, item.lastModified, item.treeUri)
            val r = SourceResult()
            when (indexer.index(spec, job.id)) {
                IndexOutcome.ADDED, IndexOutcome.RELINKED -> r.imported++
                IndexOutcome.ADDED_NEEDS_REVIEW -> {
                    r.imported++
                    r.needsReview++
                }
                IndexOutcome.ALREADY_PRESENT -> {
                    r.duplicates++
                    log(job.id, item.id, item.pathHint, EntryStatus.SKIPPED_DUPLICATE.name, "Already in the library")
                }
                IndexOutcome.UNSUPPORTED -> {
                    r.unsupported++
                    log(job.id, item.id, item.pathHint, EntryStatus.SKIPPED_UNSUPPORTED.name, "Not a supported audio type")
                }
                IndexOutcome.UNPLAYABLE -> r.fatalError = "Corrupt or unplayable audio data"
            }
            return r
        }
        return engine.importAudioFile(item.displayName, item.pathHint, item.sizeBytes) { open() }
    }

    private fun afterSuccess(job: ImportJobEntity, item: ImportItemEntity, result: SourceResult) {
        val uri = Uri.parse(item.uri)
        val deleteZip = item.kind == ItemKind.ZIP && job.deleteSourceZips && result.failed == 0
        // "Move" removes the original only after its copy was verified and committed.
        val deleteMoved = item.kind == ItemKind.AUDIO && job.audioMode == AudioMode.MOVE && result.imported == 1
        if (deleteZip || deleteMoved) {
            try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (e: Exception) {
                runBlocking { log(job.id, item.id, item.displayName, "NOTE", "Imported, but the original could not be deleted") }
            }
        }
        val keepsAccess = item.kind == ItemKind.AUDIO && job.audioMode == AudioMode.INDEX
        if (item.treeUri == null && !keepsAccess) {
            try {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // permission was never persisted
            }
        }
    }

    private suspend fun finishJob(job: ImportJobEntity) {
        if (cancelRequested) dao.cancelPending(job.id)
        val items = dao.items(job.id)
        val failedSources = items.count { it.status == ItemStatus.FAILED }
        val cancelled = items.count { it.status == ItemStatus.CANCELLED || it.status == ItemStatus.PENDING }
        val failedSongs = items.sumOf { it.failed }
        val status = when {
            cancelled > 0 -> JobStatus.CANCELLED
            failedSources > 0 || failedSongs > 0 -> JobStatus.COMPLETED_WITH_ERRORS
            else -> JobStatus.COMPLETED
        }
        val summary = buildString {
            append("${items.sumOf { it.imported }} songs added")
            val review = items.sumOf { it.needsReview }
            if (review > 0) append(" ($review need review)")
            val dup = items.sumOf { it.duplicates }
            if (dup > 0) append(", $dup duplicates skipped")
            val unsupported = items.sumOf { it.unsupported }
            if (unsupported > 0) append(", $unsupported unsupported skipped")
            if (failedSongs > 0) append(", $failedSongs files failed")
            if (failedSources > 0) append(", $failedSources of ${items.size} sources failed")
            if (cancelled > 0) append(", $cancelled sources not processed (cancelled)")
        }
        dao.updateJob(job.copy(status = status, finishedAt = System.currentTimeMillis(), summary = summary))
        lastSummary = summary
    }

    private suspend fun log(jobId: Long, itemId: Long, path: String, status: String, message: String?) {
        dao.insertLog(ImportLogEntity(jobId = jobId, itemId = itemId, path = path, status = status, message = message, time = System.currentTimeMillis()))
    }
}
