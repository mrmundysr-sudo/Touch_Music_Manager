package com.mundymorning.touchmusic.core

import java.util.Locale

/** Tags exactly as read from the file; every field may be absent. */
data class RawTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genre: String? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val compilation: Boolean = false,
)

/** The metadata the library will actually use for a track. */
data class ResolvedMetadata(
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val genre: String?,
    val year: Int?,
    val durationMs: Long,
    /** True when we could not determine who/what this is with confidence. */
    val needsReview: Boolean,
    val reviewReason: String?,
    /** True when artist or title came from the file/folder name rather than embedded tags. */
    val inferredFromPath: Boolean,
)

/** What a file's name and folders suggest about it. */
data class PathClues(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    /** Low-confidence suggestions; using them sends the track to Needs Review. */
    val guessedArtist: String? = null,
    val guessedAlbum: String? = null,
) {
    companion object {
        private val GENERIC_DIRS = setOf(
            "music", "my music", "songs", "mp3", "mp3s", "audio", "download", "downloads", "new folder",
            "untitled folder", "misc", "various", "albums", "album", "artists", "tracks", "media", "files",
            "flac", "itunes", "unknown", "other", "stuff", "temp", "tmp",
        )
        private val DISC_DIR = Regex("^(cd|disc|disk)\\s*[-_]?\\s*(\\d{1,2})$", RegexOption.IGNORE_CASE)
        private val DISC_TRACK = Regex("^(\\d)-(\\d{2})(?:\\s*[-._]\\s*|\\s+)(.+)$")
        private val TRACK_SEP = Regex("^(\\d{1,3})\\s*(?:[-_)]|\\.(?!\\d))\\s*(.+)$")
        private val TRACK_ZERO = Regex("^(0\\d{1,2})\\s+(.+)$")
        private val NUMERIC = Regex("^\\d{1,3}$")
        private val YEAR = Regex("^(19|20)\\d{2}$")
        private val BRACKETS = Regex("\\s*[(\\[]([^)\\]]*)[)\\]]")
        private val SPACES = Regex("\\s+")

        fun parse(pathHint: String): PathClues {
            val segments = pathHint.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
            val fileName = segments.lastOrNull() ?: ""
            var dirs = segments.dropLast(1)

            var disc: Int? = null
            dirs.lastOrNull()?.let { last ->
                DISC_DIR.matchEntire(last)?.let { m ->
                    disc = m.groupValues[2].toIntOrNull()
                    dirs = dirs.dropLast(1)
                }
            }
            dirs = dirs.filter { it.lowercase(Locale.ROOT) !in GENERIC_DIRS }

            // ---- file name ----
            var name = AudioFormats.stripExtension(fileName)
            if (!name.contains(' ') && name.contains('_')) name = name.replace('_', ' ')
            name = name.replace(SPACES, " ").trim()
            val fallbackTitle = name.ifEmpty { fileName }

            var track: Int? = null
            var leadingTrack = false
            var rest = name
            DISC_TRACK.matchEntire(rest)?.let { m ->
                disc = disc ?: m.groupValues[1].toIntOrNull()
                track = m.groupValues[2].toIntOrNull()
                rest = m.groupValues[3]
                leadingTrack = true
            }
            if (track == null) {
                (TRACK_SEP.matchEntire(rest) ?: TRACK_ZERO.matchEntire(rest))?.let { m ->
                    track = m.groupValues[1].toIntOrNull()
                    rest = m.groupValues[2]
                    leadingTrack = true
                }
            }
            val parts = rest.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (track == null && parts.size > 1) {
                val idx = parts.indexOfFirst { NUMERIC.matches(it) }
                if (idx >= 0) {
                    track = parts[idx].toIntOrNull()
                    parts.removeAt(idx)
                }
            }
            if (track == 0) track = null

            // ---- parent folder ----
            var folderArtist: String? = null
            var folderAlbum: String? = null
            var year: Int? = null
            var parentClean: String? = null
            dirs.lastOrNull()?.let { parent ->
                BRACKETS.findAll(parent).forEach { m ->
                    val inner = m.groupValues[1].trim()
                    if (YEAR.matches(inner)) year = inner.toInt()
                }
                val clean = parent.replace(BRACKETS, "").replace(SPACES, " ").trim()
                parentClean = clean.ifEmpty { null }
                val p = clean.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
                if (p.size >= 2) {
                    if (YEAR.matches(p[0])) {
                        year = p[0].toInt()
                        parentClean = p.drop(1).joinToString(" - ")
                    } else {
                        folderArtist = p[0]
                        folderAlbum = p.drop(1).joinToString(" - ")
                    }
                }
            }

            // ---- combine ----
            var title = fallbackTitle
            var artist: String? = folderArtist
            var guessedArtist: String? = null
            var guessedAlbum: String? = null
            when {
                parts.isEmpty() -> title = fallbackTitle
                parts.size == 1 -> title = parts[0]
                folderArtist != null && leadingTrack -> title = parts.joinToString(" - ")
                leadingTrack -> {
                    // "01 - A - B" is ambiguous: "Artist - Title" or a hyphenated title.
                    title = parts.drop(1).joinToString(" - ")
                    guessedArtist = parts[0]
                }
                else -> {
                    artist = parts[0]
                    title = parts.drop(1).joinToString(" - ")
                }
            }
            if (artist == null && guessedArtist == null && track != null && dirs.size >= 2) {
                guessedArtist = dirs[dirs.size - 2]
                guessedAlbum = parentClean
            }
            return PathClues(
                title = title.ifBlank { fallbackTitle },
                artist = artist,
                album = folderAlbum,
                trackNumber = track,
                discNumber = disc,
                year = year,
                guessedArtist = guessedArtist,
                guessedAlbum = guessedAlbum,
            )
        }
    }
}

/** Combines embedded tags with file-name clues. Never fabricates values. */
object MetadataResolver {
    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"
    const val VARIOUS_ARTISTS = "Various Artists"

    private val PLACEHOLDERS = setOf("<unknown>", "unknown artist", "unknown album", "unknown title", "untitled")

    fun clean(value: String?): String? {
        val v = value?.replace('\u0000', ' ')?.trim() ?: return null
        if (v.isEmpty() || v.lowercase(Locale.ROOT) in PLACEHOLDERS) return null
        return v
    }

    fun resolve(tags: RawTags?, pathHint: String): ResolvedMetadata {
        val clues = PathClues.parse(pathHint)
        val tagTitle = clean(tags?.title)
        val tagArtist = clean(tags?.artist)
        val tagAlbumArtist = clean(tags?.albumArtist)
        val tagAlbum = clean(tags?.album)

        val title = tagTitle ?: clues.title
        var artist = tagArtist ?: tagAlbumArtist ?: clues.artist
        var album = tagAlbum ?: if (tagArtist == null && tagAlbumArtist == null) clues.album else null
        var guessed = false
        if (artist == null && clues.guessedArtist != null) {
            artist = clues.guessedArtist
            if (album == null) album = clues.guessedAlbum
            guessed = true
        }

        val reason = when {
            artist == null -> if (tagTitle == null) "No embedded tags and no artist in the file name" else "Artist is missing"
            guessed -> "Artist guessed from file or folder names; please confirm"
            else -> null
        }
        val finalArtist = artist ?: UNKNOWN_ARTIST
        val albumArtist = tagAlbumArtist
            ?: if (tags?.compilation == true) VARIOUS_ARTISTS else finalArtist

        return ResolvedMetadata(
            title = title,
            artist = finalArtist,
            albumArtist = albumArtist,
            album = album ?: UNKNOWN_ALBUM,
            trackNumber = tags?.trackNumber?.takeIf { it > 0 } ?: clues.trackNumber,
            discNumber = tags?.discNumber?.takeIf { it > 0 } ?: clues.discNumber,
            genre = clean(tags?.genre),
            year = tags?.year?.takeIf { it in 1000..2999 } ?: clues.year,
            durationMs = tags?.durationMs?.takeIf { it > 0 } ?: 0L,
            needsReview = reason != null,
            reviewReason = reason,
            inferredFromPath = tagTitle == null || (tagArtist == null && tagAlbumArtist == null),
        )
    }
}

/** Where a track belongs inside the organised library. */
data class LibraryPlacement(val directory: String, val fileName: String) {
    val path: String get() = "$directory/$fileName"
}

object LibraryPathPlanner {
    const val ROOT = "Music"
    const val NEEDS_REVIEW_DIR = "Needs Review"

    /**
     * Music/Artist/Album/NN - Title.ext, using the album artist for the folder so that
     * compilations stay together. Uncertain files keep their original name in Music/Needs Review.
     */
    fun plan(meta: ResolvedMetadata, extension: String, originalFileName: String): LibraryPlacement {
        if (meta.needsReview) {
            val base = AudioFormats.stripExtension(originalFileName.replace('\\', '/').substringAfterLast('/'))
            return LibraryPlacement("$ROOT/$NEEDS_REVIEW_DIR", PathSanitizer.fileName(base, extension))
        }
        val dir = "$ROOT/${PathSanitizer.segment(meta.albumArtist, 100)}/${PathSanitizer.segment(meta.album, 100)}"
        val prefix = meta.trackNumber?.let { n ->
            val num = n.toString().padStart(2, '0')
            val disc = meta.discNumber?.takeIf { it > 1 }
            (if (disc != null) "$disc-$num" else num) + " - "
        } ?: ""
        return LibraryPlacement(dir, PathSanitizer.fileName(prefix + meta.title, extension))
    }
}
