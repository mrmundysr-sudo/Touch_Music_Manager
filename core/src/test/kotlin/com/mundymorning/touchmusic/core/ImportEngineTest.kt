package com.mundymorning.touchmusic.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exercises the real import engine against real ZIP data. Only the platform pieces are faked:
 * the "library" is a temp directory and tags are read from a tiny text header instead of
 * Android's MediaMetadataRetriever.
 */
class ImportEngineTest {
    private lateinit var root: File
    private lateinit var env: FakeEnv
    private val outcomes = ArrayList<EntryOutcome>()
    private val listener = object : ImportListener {
        override fun onEntry(outcome: EntryOutcome) {
            outcomes.add(outcome)
        }
    }

    @Before fun setUp() {
        root = Files.createTempDirectory("tmm-test").toFile()
        env = FakeEnv(root)
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun engine(limits: ImportLimits = ImportLimits(minFreeBytes = 1024), cancelled: () -> Boolean = { false }) =
        ImportEngine(env, limits, listener, cancelled)

    private fun import(name: String, bytes: ByteArray, limits: ImportLimits = ImportLimits(minFreeBytes = 1024)) =
        engine(limits).importArchive(name) { ByteArrayInputStream(bytes) }

    // ---------------------------------------------------------------- basics

    @Test fun zipWithSingleSong() {
        val r = import("single.zip", zip("song.mp3" to audio("One", "Solo Artist", "Single", 1)))
        assertEquals(1, r.imported)
        assertTrue(r.completedCleanly)
        assertEquals(listOf("Music/Solo Artist/Single/01 - One.mp3"), env.libraryFiles())
        assertTrue(env.tempDirIsEmpty())
    }

    @Test fun zipWithFullAlbumIsOrganisedByTags() {
        val entries = (1..12).map { "Some Folder/track$it.mp3" to audio("Song $it", "The Band", "The Album", it) }
        val r = import("album.zip", zip(*entries.toTypedArray(), "Some Folder/cover.jpg" to byteArrayOf(-1, -40, -1, 0, 1, 2)))
        assertEquals(12, r.imported)
        assertEquals(1, r.ignored)
        assertEquals(0, r.failed)
        val files = env.libraryFiles()
        assertEquals(12, files.size)
        assertTrue(files.all { it.startsWith("Music/The Band/The Album/") })
        assertTrue("Music/The Band/The Album/03 - Song 3.mp3" in files)
    }

    @Test fun mixedArchiveWithNestedFoldersNestedZipAndJunk() {
        val inner = zip(
            "01 - Inner One.flac" to audio("Inner One", "Inner Artist", "Inner Album", 1),
            "02 - Inner Two.flac" to audio("Inner Two", "Inner Artist", "Inner Album", 2),
        )
        val r = import(
            "mix.zip",
            zip(
                "a/b/c/deep.mp3" to audio("Deep", "Artist A", "Album A", 4),
                "x.m4a" to audio("Ex", "Artist B", "Album B", null),
                "comp/01.mp3" to audio("C1", "Guest 1", "Hits", 1, albumArtist = "Various Artists"),
                "comp/02.mp3" to audio("C2", "Guest 2", "Hits", 2, albumArtist = "Various Artists"),
                "albums/Inner Artist - Inner Album.zip" to inner,
                "__MACOSX/a/._deep.mp3" to byteArrayOf(0, 5, 22, 7),
                ".DS_Store" to byteArrayOf(1),
                "notes.txt" to "hello".toByteArray(),
                "old.wma" to byteArrayOf(1, 2, 3),
            ),
        )
        assertNull(r.fatalError)
        assertEquals(6, r.imported)
        assertEquals(1, r.ignored)
        assertEquals(1, r.unsupported)
        assertEquals(0, r.failed)
        assertEquals(
            listOf(
                "Music/Artist A/Album A/04 - Deep.mp3",
                "Music/Artist B/Album B/Ex.m4a",
                "Music/Inner Artist/Inner Album/01 - Inner One.flac",
                "Music/Inner Artist/Inner Album/02 - Inner Two.flac",
                "Music/Various Artists/Hits/01 - C1.mp3",
                "Music/Various Artists/Hits/02 - C2.mp3",
            ),
            env.libraryFiles(),
        )
    }

    // ------------------------------------------------------------ duplicates

    @Test fun exactDuplicatesAreSkippedWithinAndAcrossArchives() {
        val song = audio("Same", "A", "B", 1)
        val bytes = zip("one.mp3" to song, "copy of one.mp3" to song, "other.mp3" to audio("Other", "A", "B", 2))
        val first = import("a.zip", bytes)
        assertEquals(2, first.imported)
        assertEquals(1, first.duplicates)
        // Re-importing (e.g. after an interruption) must not create second copies.
        val second = import("a.zip", bytes)
        assertEquals(0, second.imported)
        assertEquals(3, second.duplicates)
        assertEquals(2, env.libraryFiles().size)
    }

    @Test fun differentFilesWithSameTagsNeverOverwrite() {
        val a = audio("Same Title", "A", "B", 1, seed = 1)
        val b = audio("Same Title", "A", "B", 1, seed = 2)
        val r = import("a.zip", zip("x.mp3" to a, "y.mp3" to b))
        assertEquals(2, r.imported)
        val files = env.libraryFiles()
        assertEquals(2, files.size)
        assertEquals(setOf(sha(a), sha(b)), files.map { sha(File(env.library, it).readBytes()) }.toSet())
    }

    // ------------------------------------------------------- missing metadata

    @Test fun untaggedFilesGoToNeedsReview() {
        val r = import(
            "random songs.zip",
            zip(
                "track_final_v2.mp3" to audio(null, null, null, null, seed = 1),
                "Daft Punk - One More Time.mp3" to audio(null, null, null, null, seed = 2),
            ),
        )
        assertEquals(2, r.imported)
        assertEquals(1, r.needsReview)
        assertEquals(
            listOf("Music/Daft Punk/Unknown Album/One More Time.mp3", "Music/Needs Review/track_final_v2.mp3"),
            env.libraryFiles(),
        )
        val review = env.commits.single { it.metadata.needsReview }
        assertEquals(MetadataResolver.UNKNOWN_ARTIST, review.metadata.artist)
    }

    @Test fun untaggedAlbumZipUsesArchiveNameAsClue() {
        val r = import(
            "Nirvana - Nevermind.zip",
            zip("01 - Smells Like Teen Spirit.mp3" to audio(null, null, null, null, seed = 1), "02 - In Bloom.mp3" to audio(null, null, null, null, seed = 2)),
        )
        assertEquals(2, r.imported)
        assertEquals(0, r.needsReview)
        assertEquals(
            listOf("Music/Nirvana/Nevermind/01 - Smells Like Teen Spirit.mp3", "Music/Nirvana/Nevermind/02 - In Bloom.mp3"),
            env.libraryFiles(),
        )
    }

    // ---------------------------------------------------------------- corrupt

    @Test fun garbageIsNotAnArchive() {
        val r = import("fake.zip", ByteArray(5000) { (it * 31).toByte() })
        assertNotNull(r.fatalError)
        assertEquals(0, r.imported)
        assertTrue(env.libraryFiles().isEmpty())
    }

    @Test fun truncatedArchiveKeepsVerifiedSongsAndReportsFailure() {
        val full = zip(
            "1.mp3" to audio("One", "A", "B", 1, payload = 200_000),
            "2.mp3" to audio("Two", "A", "B", 2, payload = 200_000),
            "3.mp3" to audio("Three", "A", "B", 3, payload = 200_000),
        )
        val r = import("cut.zip", full.copyOf(full.size / 2))
        assertNotNull(r.fatalError)
        assertFalse(r.completedCleanly)
        assertEquals(1, r.imported)
        assertEquals(listOf("Music/A/B/01 - One.mp3"), env.libraryFiles())
        assertTrue("partial temp files must be cleaned up", env.tempDirIsEmpty())
    }

    @Test fun bitFlippedArchiveNeverImportsDamagedData() {
        val good = audio("One", "A", "B", 1, payload = 300_000)
        val full = zip("1.mp3" to good)
        val damaged = full.copyOf().also { for (i in 1000 until 1200) it[i] = (it[i] + 1).toByte() }
        val r = import("damaged.zip", damaged)
        assertEquals(0, r.imported)
        assertTrue(r.fatalError != null || r.failed == 1)
        assertTrue(env.libraryFiles().isEmpty())
    }

    @Test fun corruptAndFakeAudioEntriesFailIndividually() {
        val r = import(
            "bad.zip",
            zip(
                "good.mp3" to audio("Good", "A", "B", 1),
                "broken.mp3" to "ID3\nCORRUPT\n".toByteArray(),
                "empty.mp3" to ByteArray(0),
                "renamed.mp3" to zip("x.txt" to byteArrayOf(1)),
                "virus.mp3" to byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0x90.toByte(), 0, 3, 0),
            ),
        )
        assertNull(r.fatalError)
        assertEquals(1, r.imported)
        assertEquals(4, r.failed)
        assertEquals(1, env.libraryFiles().size)
        assertEquals(4, outcomes.count { it.status == EntryStatus.FAILED && it.message != null })
    }

    @Test fun corruptNestedArchiveOnlyFailsThatEntry() {
        val inner = zip("i.mp3" to audio("I", "A", "B", 1, payload = 100_000))
        val r = import(
            "outer.zip",
            zip(
                "before.mp3" to audio("Before", "A", "B", 2),
                "inner.zip" to inner.copyOf(inner.size / 2),
                "notzip.zip" to ByteArray(300) { 7 },
                "after.mp3" to audio("After", "A", "B", 3),
            ),
        )
        assertNull(r.fatalError)
        assertEquals(2, r.imported)
        assertEquals(2, r.failed)
    }

    // --------------------------------------------------------------- security

    @Test fun zipSlipEntriesAreBlocked() {
        val r = import(
            "evil.zip",
            zip(
                "../../../evil.mp3" to audio("Evil", "E", "E", 1, seed = 1),
                "/abs/evil2.mp3" to audio("Evil2", "E", "E", 2, seed = 2),
                "ok/..\\..\\evil3.mp3" to audio("Evil3", "E", "E", 3, seed = 3),
                "fine.mp3" to audio("Fine", "F", "F", 1, seed = 4),
            ),
        )
        assertEquals(1, r.imported)
        assertEquals(3, r.failed)
        assertEquals(listOf("Music/F/F/01 - Fine.mp3"), env.libraryFiles())
        // Nothing may exist outside the library and temp directories.
        assertEquals(setOf("library", "tmp"), root.list()!!.toSet())
        assertFalse(File(root.parentFile, "evil.mp3").exists())
    }

    @Test fun hostileTagsCannotEscapeTheLibrary() {
        val r = import("t.zip", zip("a.mp3" to audio("../../x", "../..", "/etc", 1)))
        assertEquals(1, r.imported)
        val f = env.libraryFiles().single()
        assertTrue(f.startsWith("Music/"))
        assertTrue(File(env.library, f).canonicalPath.startsWith(env.library.canonicalPath))
    }

    @Test fun zipBombByRatioIsRejected() {
        val bomb = zip("boom.mp3" to ByteArray(64 * 1024 * 1024), "after.mp3" to audio("After", "A", "B", 1))
        assertTrue(bomb.size < 200_000)
        val r = import("bomb.zip", bomb, ImportLimits(minFreeBytes = 1024, ratioCheckFloorBytes = 4L * 1024 * 1024))
        assertNotNull(r.fatalError)
        assertTrue(r.fatalError!!.contains("bomb", ignoreCase = true))
        assertEquals(0, r.imported)
        assertTrue(env.libraryFiles().isEmpty())
        assertTrue(env.tempDirIsEmpty())
    }

    @Test fun zipBombInIgnoredEntryIsRejected() {
        val r = import("bomb.zip", zip("readme.txt" to ByteArray(64 * 1024 * 1024)), ImportLimits(minFreeBytes = 1024, ratioCheckFloorBytes = 4L * 1024 * 1024))
        assertNotNull(r.fatalError)
    }

    @Test fun entryCountAndSizeLimitsAreEnforced() {
        val many = zip(*(1..50).map { "f$it.txt" to byteArrayOf(1) }.toTypedArray())
        assertNotNull(import("many.zip", many, ImportLimits(minFreeBytes = 1024, maxEntries = 20)).fatalError)
        val big = zip("big.mp3" to audio("Big", "A", "B", 1, payload = 300_000))
        val r = import("big.zip", big, ImportLimits(minFreeBytes = 1024, maxEntryBytes = 100_000))
        assertNotNull(r.fatalError)
        assertTrue(env.libraryFiles().isEmpty())
    }

    @Test fun nestingDepthIsLimited() {
        var data = zip("deep.mp3" to audio("Deep", "A", "B", 1))
        repeat(5) { data = zip("level.zip" to data) }
        val r = import("nest.zip", data)
        assertEquals(0, r.imported)
        assertEquals(1, r.failed)
    }

    // ---------------------------------------------------------------- storage

    @Test fun stopsWhenStorageIsInsufficient() {
        env.available = 50_000
        val r = import("a.zip", zip("1.mp3" to audio("One", "A", "B", 1)), ImportLimits(minFreeBytes = 100_000))
        assertNotNull(r.fatalError)
        assertTrue(r.fatalError!!.contains("storage", ignoreCase = true))
        assertEquals(0, r.imported)
    }

    @Test fun commitFailureIsReportedAndTempIsCleaned() {
        env.failCommits = true
        val r = import("a.zip", zip("1.mp3" to audio("One", "A", "B", 1)))
        assertEquals(0, r.imported)
        assertEquals(1, r.failed)
        assertTrue(env.tempDirIsEmpty())
    }

    // ------------------------------------------------------------ cancellation

    @Test fun cancellationStopsCleanly() {
        val entries = (1..20).map { "t$it.mp3" to audio("S$it", "A", "B", it) }
        var cancel = false
        val l = object : ImportListener {
            var count = 0
            override fun onEntry(outcome: EntryOutcome) {
                if (++count == 5) cancel = true
            }
        }
        val r = ImportEngine(env, ImportLimits(minFreeBytes = 1024), l) { cancel }
            .importArchive("c.zip") { ByteArrayInputStream(zip(*entries.toTypedArray())) }
        assertTrue(r.cancelled)
        assertEquals(5, r.imported)
        assertEquals(5, env.libraryFiles().size)
        assertTrue(env.tempDirIsEmpty())
        // A retry finishes the job without duplicating the first five.
        cancel = false
        val retry = import("c.zip", zip(*entries.toTypedArray()))
        assertEquals(15, retry.imported)
        assertEquals(5, retry.duplicates)
        assertEquals(20, env.libraryFiles().size)
    }

    // ------------------------------------------------------------------ scale

    @Test fun batchOfOneHundredTwentyArchives() {
        var imported = 0
        var failedSources = 0
        for (i in 1..120) {
            val bytes = if (i % 40 == 0) ByteArray(100) { 3 } else zip(
                *(1..3).map { t -> "t$t.mp3" to audio("Song $t", "Artist $i", "Album $i", t, seed = i * 10 + t) }.toTypedArray(),
            )
            val r = import("album$i.zip", bytes)
            imported += r.imported
            if (r.fatalError != null) failedSources++
        }
        assertEquals(117 * 3, imported)
        assertEquals(3, failedSources)
        assertEquals(117 * 3, env.libraryFiles().size)
        assertTrue(env.tempDirIsEmpty())
    }

    @Test fun largeMixedArchiveWithThousandsOfSongs() {
        val entries = (1..3000).map { i ->
            "dump/folder${i % 37}/file$i.mp3" to audio("Title $i", "Artist ${i % 150}", "Album ${i % 400}", (i % 20) + 1, seed = i, payload = 256)
        }
        val r = import("huge.zip", zip(*entries.toTypedArray()))
        assertEquals(3000, r.imported)
        assertEquals(0, r.failed)
        assertEquals(3000, env.libraryFiles().size)
    }

    // --------------------------------------------------------------- encoding

    @Test fun legacyCodePageNamesAreHandled() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos, Charset.forName("IBM437")).use { z ->
            z.putNextEntry(ZipEntry("first.mp3")); z.write(audio("First", "A", "B", 1, seed = 1)); z.closeEntry()
            z.putNextEntry(ZipEntry("Beyoncé - Déjà Vu.mp3")); z.write(audio(null, null, null, null, seed = 2)); z.closeEntry()
            z.putNextEntry(ZipEntry("last.mp3")); z.write(audio("Last", "A", "B", 3, seed = 3)); z.closeEntry()
        }
        val r = import("legacy.zip", bos.toByteArray())
        assertNull(r.fatalError)
        assertEquals(3, r.imported)
        assertEquals(0, r.duplicates)
        assertTrue(env.libraryFiles().any { it.contains("Beyonc") })
    }

    // ------------------------------------------------------------- loose files

    @Test fun looseAudioFiles() {
        val song = audio("Loose", "A", "B", 9)
        val e = engine()
        val ok = e.importAudioFile("whatever.mp3", "Music/whatever.mp3", song.size.toLong()) { ByteArrayInputStream(song) }
        assertEquals(1, ok.imported)
        val dup = e.importAudioFile("again.mp3", "again.mp3", song.size.toLong()) { ByteArrayInputStream(song) }
        assertEquals(1, dup.duplicates)
        assertEquals(1, e.importAudioFile("x.wma", "x.wma", 3) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }.unsupported)
        assertEquals(1, e.importAudioFile("x.pdf", "x.pdf", 3) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }.failed)
        val unreadable = e.importAudioFile("gone.mp3", "gone.mp3", 3) { throw java.io.FileNotFoundException("gone") }
        assertNotNull(unreadable.fatalError)
        assertEquals(listOf("Music/A/B/09 - Loose.mp3"), env.libraryFiles())
    }

    // ---------------------------------------------------------------- helpers

    private fun sha(b: ByteArray) = ImportEngine.sha256(ByteArrayInputStream(b))

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Fake audio: an "ID3" magic, key=value tag lines, then incompressible payload. */
    private fun audio(
        title: String?, artist: String?, album: String?, track: Int?,
        albumArtist: String? = null, seed: Int = (title + artist + album + track).hashCode(), payload: Int = 2048,
    ): ByteArray {
        val sb = StringBuilder("ID3\n")
        title?.let { sb.append("TITLE=$it\n") }
        artist?.let { sb.append("ARTIST=$it\n") }
        album?.let { sb.append("ALBUM=$it\n") }
        albumArtist?.let { sb.append("ALBUMARTIST=$it\n") }
        track?.let { sb.append("TRACK=$it\n") }
        sb.append("--\n")
        val noise = ByteArray(payload).also { Random(seed.toLong()).nextBytes(it) }
        return sb.toString().toByteArray() + noise
    }

    class FakeEnv(root: File) : ImportEnvironment {
        val library = File(root, "library").apply { mkdirs() }
        private val tmp = File(root, "tmp").apply { mkdirs() }
        private val hashes = HashSet<String>()
        val commits = ArrayList<CommitRequest>()
        var available = Long.MAX_VALUE / 4
        var failCommits = false
        private var counter = 0

        override fun availableBytes() = available
        override fun createTempFile(extension: String) = File(tmp, "x${counter++}.$extension.part")

        override fun probe(file: File, extension: String): ProbeResult {
            val text = String(file.readBytes().copyOf(minOf(file.length().toInt(), 600)), Charsets.UTF_8)
            if (!text.startsWith("ID3\n") || "CORRUPT" in text) return ProbeResult(false, null)
            val tags = text.substringBefore("--\n").lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            return ProbeResult(
                true,
                RawTags(tags["TITLE"], tags["ARTIST"], tags["ALBUMARTIST"], tags["ALBUM"], tags["TRACK"]?.toInt(), durationMs = 1000),
            )
        }

        override fun isDuplicate(sha256: String, sizeBytes: Long) = sha256 in hashes

        override fun commit(request: CommitRequest) {
            if (failCommits) throw java.io.IOException("disk error")
            val dir = File(library, request.placement.directory).apply { mkdirs() }
            var target = File(dir, request.placement.fileName)
            var n = 1
            while (target.exists()) {
                target = File(dir, AudioFormats.stripExtension(request.placement.fileName) + " (${n++})." + request.extension)
            }
            request.tempFile.copyTo(target, overwrite = false)
            hashes.add(request.sha256)
            commits.add(request)
        }

        fun libraryFiles(): List<String> =
            library.walkTopDown().filter { it.isFile }.map { it.relativeTo(library).path.replace(File.separatorChar, '/') }.sorted().toList()

        fun tempDirIsEmpty() = tmp.list().isNullOrEmpty()
    }
}
