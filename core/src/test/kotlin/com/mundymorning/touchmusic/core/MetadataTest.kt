package com.mundymorning.touchmusic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTest {
    @Test fun fullTagsWin() {
        val m = MetadataResolver.resolve(
            RawTags(title = "Come Together", artist = "The Beatles", album = "Abbey Road", trackNumber = 1, year = 1969, durationMs = 259000),
            "junk/whatever.mp3",
        )
        assertEquals("Come Together", m.title)
        assertEquals("The Beatles", m.artist)
        assertEquals("The Beatles", m.albumArtist)
        assertEquals("Abbey Road", m.album)
        assertFalse(m.needsReview)
        assertFalse(m.inferredFromPath)
        assertEquals("Music/The Beatles/Abbey Road/01 - Come Together.mp3", LibraryPathPlanner.plan(m, "mp3", "whatever.mp3").path)
    }

    @Test fun compilationUsesAlbumArtist() {
        val tagged = MetadataResolver.resolve(
            RawTags(title = "Song", artist = "Someone", albumArtist = "Various Artists", album = "Hits 99", trackNumber = 7), "x.mp3",
        )
        assertEquals("Music/Various Artists/Hits 99/07 - Song.mp3", LibraryPathPlanner.plan(tagged, "mp3", "x.mp3").path)
        val flagged = MetadataResolver.resolve(RawTags(title = "Song", artist = "Someone", album = "Hits 99", compilation = true), "x.mp3")
        assertEquals("Various Artists", flagged.albumArtist)
        assertEquals("Someone", flagged.artist)
    }

    @Test fun discNumberInFileName() {
        val m = MetadataResolver.resolve(RawTags(title = "T", artist = "A", album = "B", trackNumber = 3, discNumber = 2), "x.flac")
        assertEquals("Music/A/B/2-03 - T.flac", LibraryPathPlanner.plan(m, "flac", "x.flac").path)
    }

    @Test fun artistTitleFileNameIsAccepted() {
        val m = MetadataResolver.resolve(null, "Downloads/Daft Punk - Around the World.mp3")
        assertEquals("Daft Punk", m.artist)
        assertEquals("Around the World", m.title)
        assertEquals(MetadataResolver.UNKNOWN_ALBUM, m.album)
        assertFalse(m.needsReview)
        assertTrue(m.inferredFromPath)
    }

    @Test fun artistAlbumFolderPattern() {
        val m = MetadataResolver.resolve(null, "Pink Floyd - The Wall (1979) [FLAC]/CD2/03 - Hey You.flac")
        assertEquals("Pink Floyd", m.artist)
        assertEquals("The Wall", m.album)
        assertEquals("Hey You", m.title)
        assertEquals(3, m.trackNumber)
        assertEquals(2, m.discNumber)
        assertEquals(1979, m.year)
        assertFalse(m.needsReview)
    }

    @Test fun hyphenatedTitleInsideKnownAlbumFolder() {
        val m = MetadataResolver.resolve(null, "Queen - Greatest Hits/05 - Somebody to Love - Remastered.mp3")
        assertEquals("Queen", m.artist)
        assertEquals("Somebody to Love - Remastered", m.title)
        assertFalse(m.needsReview)
    }

    @Test fun bareFileNameGoesToNeedsReviewWithoutInventing() {
        val m = MetadataResolver.resolve(RawTags(durationMs = 1000), "big mix/track_final_v2.mp3")
        assertTrue(m.needsReview)
        assertEquals(MetadataResolver.UNKNOWN_ARTIST, m.artist)
        assertEquals("track final v2", m.title)
        assertEquals("Music/Needs Review/track_final_v2.mp3", LibraryPathPlanner.plan(m, "mp3", "track_final_v2.mp3").path)
    }

    @Test fun folderStructureGuessIsFlaggedForReview() {
        val m = MetadataResolver.resolve(null, "Collection/Radiohead/OK Computer/02 Paranoid Android.mp3")
        assertEquals("Radiohead", m.artist)
        assertEquals("OK Computer", m.album)
        assertEquals(2, m.trackNumber)
        assertTrue(m.needsReview)
    }

    @Test fun placeholdersAreNotTreatedAsMetadata() {
        val m = MetadataResolver.resolve(RawTags(title = "  ", artist = "<unknown>"), "song.mp3")
        assertTrue(m.needsReview)
        assertEquals("song", m.title)
    }

    @Test fun titleStartingWithNumberIsNotATrackNumber() {
        assertNull(PathClues.parse("99 Problems.mp3").trackNumber)
        assertEquals("99 Problems", PathClues.parse("99 Problems.mp3").title)
        assertEquals("1979", PathClues.parse("1979.mp3").title)
        val c = PathClues.parse("07. Karma Police.mp3")
        assertEquals(7, c.trackNumber)
        assertEquals("Karma Police", c.title)
    }

    @Test fun tagArtistWithoutAlbumDoesNotBorrowFolderGuess() {
        val m = MetadataResolver.resolve(RawTags(title = "T", artist = "A"), "Stuff/Some Folder/01 T.mp3")
        assertEquals(MetadataResolver.UNKNOWN_ALBUM, m.album)
        assertFalse(m.needsReview)
    }

    @Test fun sanitizerBlocksTraversal() {
        assertNull(PathSanitizer.normalizeEntryPath("../../etc/passwd"))
        assertNull(PathSanitizer.normalizeEntryPath("a/../../b.mp3"))
        assertNull(PathSanitizer.normalizeEntryPath("/abs/b.mp3"))
        assertNull(PathSanitizer.normalizeEntryPath("C:\\Windows\\x.mp3"))
        assertNull(PathSanitizer.normalizeEntryPath("..\\x.mp3"))
        assertEquals("a/b.mp3", PathSanitizer.normalizeEntryPath("a\\.\\b.mp3"))
        assertEquals("a/b.mp3", PathSanitizer.normalizeEntryPath("./a//b.mp3"))
    }

    @Test fun sanitizerMakesSafeSegments() {
        assertEquals("AC_DC", PathSanitizer.segment("AC/DC"))
        assertEquals("What_", PathSanitizer.segment("What?"))
        assertEquals("_", PathSanitizer.segment("..."))
        assertEquals("hidden", PathSanitizer.segment(".hidden"))
        assertEquals("a b", PathSanitizer.segment("a \t\n b"))
        val long = PathSanitizer.fileName("é".repeat(400), "mp3")
        assertTrue(long.toByteArray().size <= 150)
        assertTrue(long.endsWith(".mp3"))
        val m = MetadataResolver.resolve(RawTags(title = "a/b:c", artist = "AC/DC", album = "..\\.."), "x.mp3")
        val p = LibraryPathPlanner.plan(m, "mp3", "x.mp3")
        assertEquals(3, p.directory.split('/').size)
        assertFalse(p.path.contains(".."))
        assertFalse(p.fileName.contains('/'))
    }

    @Test fun sniffing() {
        fun s(vararg b: Int) = AudioFormats.sniff(ByteArray(16).also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } }, 16)
        assertEquals(AudioFormats.Sniff.AUDIO, s('I'.code, 'D'.code, '3'.code, 4))
        assertEquals(AudioFormats.Sniff.AUDIO, s(0xFF, 0xFB, 0x90, 0x00))
        assertEquals(AudioFormats.Sniff.AUDIO, s(0, 0, 0, 0x20, 'f'.code, 't'.code, 'y'.code, 'p'.code))
        assertEquals(AudioFormats.Sniff.NOT_AUDIO, s(0x50, 0x4B, 3, 4))
        assertEquals(AudioFormats.Sniff.NOT_AUDIO, s(0xFF, 0xD8, 0xFF, 0xE0))
        assertEquals(AudioFormats.Sniff.UNKNOWN, s(1, 2, 3, 4))
        assertEquals("mp3", AudioFormats.extensionOf("a/b.c/Song.MP3"))
        assertEquals("", AudioFormats.extensionOf(".hidden"))
    }
}
