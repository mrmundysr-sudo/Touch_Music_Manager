package com.mundymorning.touchmusic.core

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Safety limits applied while reading archives. */
data class ImportLimits(
    /** Maximum number of entries (including nested archives) in one top-level archive. */
    val maxEntries: Int = 100_000,
    /** Maximum size of one extracted file. */
    val maxEntryBytes: Long = 2L * 1024 * 1024 * 1024,
    /** Maximum total bytes read out of one top-level archive. */
    val maxTotalBytes: Long = 200L * 1024 * 1024 * 1024,
    /** Uncompressed/compressed ratio above which the archive is treated as a ZIP bomb. */
    val maxCompressionRatio: Long = 150,
    /** The ratio is only enforced once this much data has been produced. */
    val ratioCheckFloorBytes: Long = 32L * 1024 * 1024,
    /** ZIP-inside-ZIP depth that will be followed. */
    val maxNestingDepth: Int = 3,
    /** Free space that must remain on the device at all times. */
    val minFreeBytes: Long = 100L * 1024 * 1024,
)

enum class EntryStatus {
    IMPORTED,
    IMPORTED_NEEDS_REVIEW,
    SKIPPED_DUPLICATE,
    SKIPPED_UNSUPPORTED,
    IGNORED_NOT_AUDIO,
    FAILED,
}

data class EntryOutcome(val path: String, val status: EntryStatus, val message: String? = null)

/** Totals for one source (one archive or one loose audio file). */
class SourceResult {
    var imported = 0
    var needsReview = 0
    var duplicates = 0
    var unsupported = 0
    var ignored = 0
    var failed = 0
    /** Set when the source as a whole could not be completed (corrupt, bomb, out of space…). */
    var fatalError: String? = null
    var cancelled = false

    val completedCleanly: Boolean get() = fatalError == null && !cancelled && failed == 0
    override fun toString(): String =
        "SourceResult(imported=$imported, needsReview=$needsReview, duplicates=$duplicates, unsupported=$unsupported, " +
            "ignored=$ignored, failed=$failed, fatalError=$fatalError, cancelled=$cancelled)"
}

data class ProbeResult(val playable: Boolean, val tags: RawTags?)

data class CommitRequest(
    val tempFile: File,
    val sha256: String,
    val sizeBytes: Long,
    val extension: String,
    val metadata: ResolvedMetadata,
    val placement: LibraryPlacement,
    /** Human-readable origin, e.g. "Album.zip/CD1/01 Song.mp3". */
    val origin: String,
)

/** Everything the engine needs from the platform. Implemented with Android APIs in the app. */
interface ImportEnvironment {
    fun availableBytes(): Long
    fun createTempFile(extension: String): File
    /** Decides whether the extracted file is playable audio and reads its tags. */
    fun probe(file: File, extension: String): ProbeResult
    /** True when a file with exactly this content is already in the library. */
    fun isDuplicate(sha256: String, sizeBytes: Long): Boolean
    /** Copies the verified temp file into the library. Must never overwrite; throws on failure. */
    fun commit(request: CommitRequest)
}

interface ImportListener {
    fun onEntry(outcome: EntryOutcome) {}
    fun onProgress(currentPath: String, bytesProcessed: Long) {}

    companion object {
        val NONE = object : ImportListener {}
    }
}

class ImportAbortException(message: String) : RuntimeException(message)
class CancelledImportException : RuntimeException("Cancelled")

/**
 * Streams archives and loose audio files into the library.
 *
 * Archives are read strictly sequentially with [ZipInputStream]; nothing is held in memory
 * beyond one copy buffer. Every audio entry is extracted to a temporary file while being
 * hashed, verified, checked against existing library content and only then committed.
 * Entry names are never used as output paths, so ZIP-slip cannot write outside the library;
 * entries with traversal names are additionally rejected and reported.
 *
 * Extraction is not resumable inside an archive: an interrupted archive is restarted from
 * its first entry, and songs already committed are then skipped as duplicates.
 */
class ImportEngine(
    private val env: ImportEnvironment,
    private val limits: ImportLimits = ImportLimits(),
    private val listener: ImportListener = ImportListener.NONE,
    private val isCancelled: () -> Boolean = { false },
) {
    private class ArchiveState(val limits: ImportLimits) {
        var entriesSeen = 0
        var totalOut = 0L
        var compressedDone = 0L
        var counter: CountingInputStream? = null

        fun addOutput(n: Int) {
            totalOut += n
            if (totalOut > limits.maxTotalBytes) {
                throw ImportAbortException("Archive expands beyond the ${limits.maxTotalBytes / (1024 * 1024 * 1024)} GB safety limit")
            }
            val c = counter ?: return
            if (totalOut > limits.ratioCheckFloorBytes) {
                val compressed = (compressedDone + c.count).coerceAtLeast(1)
                if (totalOut / compressed > limits.maxCompressionRatio) {
                    throw ImportAbortException("Suspicious compression ratio (possible ZIP bomb); archive rejected")
                }
            }
        }
    }

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }

    /** Marks a read failure of the archive at [level] (0 = top-level archive). */
    private class ArchiveReadException(val level: Int, cause: IOException) :
        IOException(cause.message ?: cause.javaClass.simpleName, cause)

    private class MalformedEntryNameException(val entriesRead: Int) : IOException("Entry name is not valid in this encoding")

    /** Wraps an archive stream so read errors are attributed to it; never closes the parent. */
    private class GuardedInputStream(private val inner: InputStream, private val level: Int) : InputStream() {
        override fun read(): Int = try {
            inner.read()
        } catch (e: ArchiveReadException) {
            throw e
        } catch (e: IOException) {
            throw ArchiveReadException(level, e)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            inner.read(b, off, len)
        } catch (e: ArchiveReadException) {
            throw e
        } catch (e: IOException) {
            throw ArchiveReadException(level, e)
        }

        override fun close() = Unit
    }

    /** Imports every supported audio file found in a ZIP archive (including nested ZIPs). */
    fun importArchive(displayName: String, open: () -> InputStream): SourceResult {
        val result = SourceResult()
        val state = ArchiveState(limits)
        val rootHint = PathSanitizer.segment(AudioFormats.stripExtension(displayName))
        var charset: Charset = Charsets.UTF_8
        var skip = 0
        var attempt = 0
        try {
            while (true) {
                attempt++
                try {
                    open().use { raw ->
                        val counting = CountingInputStream(BufferedInputStream(raw, BUFFER))
                        state.counter = counting
                        try {
                            walk(ZipInputStream(counting, charset), rootHint, 0, state, result, skip)
                        } finally {
                            state.compressedDone += counting.count
                            state.counter = null
                        }
                    }
                    break
                } catch (e: MalformedEntryNameException) {
                    // Legacy archives store names in a DOS code page. Re-open and continue after
                    // the entries already handled.
                    if (attempt >= 2) throw IOException("Archive entry names could not be decoded")
                    charset = legacyCharset()
                    skip = e.entriesRead
                }
            }
            if (state.entriesSeen == 0 && skip == 0) {
                result.fatalError = "Not a valid ZIP archive (or it is empty)"
            }
        } catch (e: CancelledImportException) {
            result.cancelled = true
        } catch (e: ImportAbortException) {
            result.fatalError = e.message
        } catch (e: IOException) {
            result.fatalError = "Archive is corrupt or unreadable: ${e.message ?: e.javaClass.simpleName}"
        } catch (e: IllegalArgumentException) {
            result.fatalError = "Archive is corrupt or unreadable: ${e.message ?: e.javaClass.simpleName}"
        }
        return result
    }

    /** Copies one loose audio file into the library through the same verify-then-commit path. */
    fun importAudioFile(displayName: String, pathHint: String, declaredSize: Long, open: () -> InputStream): SourceResult {
        val result = SourceResult()
        val ext = AudioFormats.extensionOf(displayName)
        try {
            when {
                AudioFormats.isKnownUnsupportedAudio(ext) ->
                    record(result, EntryOutcome(pathHint, EntryStatus.SKIPPED_UNSUPPORTED, "Android cannot play .$ext files"))
                !AudioFormats.isSupportedAudio(ext) ->
                    record(result, EntryOutcome(pathHint, EntryStatus.FAILED, "Not a supported audio file type"))
                else -> open().use { raw ->
                    importStream(BufferedInputStream(raw, BUFFER), pathHint, displayName, ext, declaredSize, ArchiveState(limits), result)
                }
            }
        } catch (e: CancelledImportException) {
            result.cancelled = true
        } catch (e: ImportAbortException) {
            result.fatalError = e.message
        } catch (e: IOException) {
            result.fatalError = "Could not read file: ${e.message ?: e.javaClass.simpleName}"
        } catch (e: SecurityException) {
            result.fatalError = "Permission to read this file was lost"
        }
        return result
    }

    private fun walk(zis: ZipInputStream, hintPrefix: String, depth: Int, state: ArchiveState, result: SourceResult, skip: Int) {
        var index = 0
        while (true) {
            checkCancelled()
            val entry = try {
                zis.nextEntry
            } catch (e: IllegalArgumentException) {
                throw MalformedEntryNameException(index)
            } catch (e: ZipException) {
                val m = e.message.orEmpty().lowercase()
                if ("bad entry name" in m || "malformed" in m) throw MalformedEntryNameException(index) else throw e
            } ?: break
            index++
            if (index <= skip) continue
            state.entriesSeen++
            if (state.entriesSeen > limits.maxEntries) {
                throw ImportAbortException("Archive has more than ${limits.maxEntries} entries (possible ZIP bomb); archive rejected")
            }
            if (entry.isDirectory) continue

            val rawName = entry.name
            val safe = PathSanitizer.normalizeEntryPath(rawName)
            if (safe == null) {
                drain(zis, depth, state)
                record(result, EntryOutcome("$hintPrefix/${rawName.take(200)}", EntryStatus.FAILED, "Unsafe path inside archive was blocked"))
                continue
            }
            val hint = "$hintPrefix/$safe"
            val fileName = safe.substringAfterLast('/')
            if (isJunk(safe, fileName)) {
                drain(zis, depth, state)
                continue
            }
            val ext = AudioFormats.extensionOf(fileName)
            when {
                AudioFormats.isSupportedAudio(ext) ->
                    importStream(GuardedInputStream(zis, depth), hint, fileName, ext, entry.size, state, result)

                AudioFormats.isZip(ext) -> {
                    if (depth + 1 > limits.maxNestingDepth) {
                        drain(zis, depth, state)
                        record(result, EntryOutcome(hint, EntryStatus.FAILED, "Archive nested too deeply; not opened"))
                    } else {
                        val before = state.entriesSeen
                        try {
                            val inner = ZipInputStream(GuardedInputStream(zis, depth), Charsets.UTF_8)
                            walk(inner, "$hintPrefix/${AudioFormats.stripExtension(safe)}", depth + 1, state, result, 0)
                            if (state.entriesSeen == before) {
                                record(result, EntryOutcome(hint, EntryStatus.FAILED, "Nested archive is empty or not a valid ZIP"))
                            }
                        } catch (e: ArchiveReadException) {
                            if (e.level <= depth) throw e
                            record(result, EntryOutcome(hint, EntryStatus.FAILED, "Nested archive is corrupt: ${e.message}"))
                        } catch (e: IOException) {
                            record(result, EntryOutcome(hint, EntryStatus.FAILED, "Nested archive is corrupt: ${e.message}"))
                        } catch (e: IllegalArgumentException) {
                            record(result, EntryOutcome(hint, EntryStatus.FAILED, "Nested archive is corrupt: ${e.message}"))
                        }
                    }
                }

                AudioFormats.isKnownUnsupportedAudio(ext) -> {
                    drain(zis, depth, state)
                    record(result, EntryOutcome(hint, EntryStatus.SKIPPED_UNSUPPORTED, "Android cannot play .$ext files"))
                }

                else -> {
                    drain(zis, depth, state)
                    record(result, EntryOutcome(hint, EntryStatus.IGNORED_NOT_AUDIO))
                }
            }
        }
    }

    private fun importStream(
        input: InputStream,
        hint: String,
        originalName: String,
        ext: String,
        declaredSize: Long,
        state: ArchiveState,
        result: SourceResult,
    ) {
        val need = declaredSize.coerceAtLeast(0) + limits.minFreeBytes
        if (env.availableBytes() < need) {
            throw ImportAbortException("Not enough free storage to continue (need ${need / (1024 * 1024)} MB free)")
        }
        val temp = env.createTempFile(ext)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(16)
            var headerLen = 0
            var size = 0L
            var sinceCheck = 0L
            FileOutputStream(temp).use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    checkCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    if (headerLen < header.size) {
                        val take = minOf(header.size - headerLen, n)
                        System.arraycopy(buf, 0, header, headerLen, take)
                        headerLen += take
                    }
                    size += n
                    if (size > limits.maxEntryBytes) {
                        throw ImportAbortException("A file is larger than the ${limits.maxEntryBytes / (1024 * 1024)} MB per-file safety limit")
                    }
                    state.addOutput(n)
                    try {
                        out.write(buf, 0, n)
                    } catch (e: IOException) {
                        throw ImportAbortException("Could not write temporary file (storage full?): ${e.message}")
                    }
                    digest.update(buf, 0, n)
                    sinceCheck += n
                    if (sinceCheck >= PROGRESS_STEP) {
                        sinceCheck = 0
                        if (env.availableBytes() < limits.minFreeBytes) {
                            throw ImportAbortException("Storage is almost full; import stopped")
                        }
                        listener.onProgress(hint, state.totalOut)
                    }
                }
            }
            listener.onProgress(hint, state.totalOut)
            if (size == 0L) {
                record(result, EntryOutcome(hint, EntryStatus.FAILED, "File is empty"))
                return
            }
            if (temp.length() != size) {
                record(result, EntryOutcome(hint, EntryStatus.FAILED, "Extraction could not be verified (size mismatch)"))
                return
            }
            if (AudioFormats.sniff(header, headerLen) == AudioFormats.Sniff.NOT_AUDIO) {
                record(result, EntryOutcome(hint, EntryStatus.FAILED, "Content is not audio despite the .$ext extension"))
                return
            }
            val probe = try {
                env.probe(temp, ext)
            } catch (e: Exception) {
                ProbeResult(false, null)
            }
            if (!probe.playable) {
                record(result, EntryOutcome(hint, EntryStatus.FAILED, "Corrupt or unplayable audio data"))
                return
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (env.isDuplicate(sha, size)) {
                record(result, EntryOutcome(hint, EntryStatus.SKIPPED_DUPLICATE, "Identical file is already in the library"))
                return
            }
            val meta = MetadataResolver.resolve(probe.tags, hint)
            val placement = LibraryPathPlanner.plan(meta, ext, originalName)
            try {
                env.commit(CommitRequest(temp, sha, size, ext, meta, placement, hint))
            } catch (e: Exception) {
                record(result, EntryOutcome(hint, EntryStatus.FAILED, "Could not save to library: ${e.message ?: e.javaClass.simpleName}"))
                return
            }
            record(
                result,
                EntryOutcome(hint, if (meta.needsReview) EntryStatus.IMPORTED_NEEDS_REVIEW else EntryStatus.IMPORTED, meta.reviewReason),
            )
        } finally {
            temp.delete()
        }
    }

    /** Reads past an entry we do not extract, still enforcing the bomb limits. */
    private fun drain(zis: ZipInputStream, depth: Int, state: ArchiveState) {
        val guarded = GuardedInputStream(zis, depth)
        val buf = ByteArray(BUFFER)
        var size = 0L
        while (true) {
            checkCancelled()
            val n = guarded.read(buf)
            if (n < 0) break
            size += n
            if (size > limits.maxEntryBytes) {
                throw ImportAbortException("An archive entry exceeds the per-file safety limit (possible ZIP bomb)")
            }
            state.addOutput(n)
        }
    }

    private fun record(result: SourceResult, outcome: EntryOutcome) {
        when (outcome.status) {
            EntryStatus.IMPORTED -> result.imported++
            EntryStatus.IMPORTED_NEEDS_REVIEW -> {
                result.imported++
                result.needsReview++
            }
            EntryStatus.SKIPPED_DUPLICATE -> result.duplicates++
            EntryStatus.SKIPPED_UNSUPPORTED -> result.unsupported++
            EntryStatus.IGNORED_NOT_AUDIO -> result.ignored++
            EntryStatus.FAILED -> result.failed++
        }
        listener.onEntry(outcome)
    }

    private fun isJunk(path: String, fileName: String): Boolean =
        path.startsWith("__MACOSX/") || "/__MACOSX/" in path || fileName.startsWith("._") ||
            fileName == ".DS_Store" || fileName.equals("Thumbs.db", true) || fileName.equals("desktop.ini", true)

    private fun checkCancelled() {
        if (isCancelled()) throw CancelledImportException()
    }

    private fun legacyCharset(): Charset = try {
        Charset.forName("IBM437")
    } catch (e: Exception) {
        Charsets.ISO_8859_1
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_STEP = 4L * 1024 * 1024

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
