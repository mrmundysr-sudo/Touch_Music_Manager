package com.mundymorning.touchmusic.core

/** Makes names safe for the filesystem and validates paths that come from archives. */
object PathSanitizer {
    private const val ILLEGAL = "<>:\"/\\|?*"
    private val WHITESPACE = Regex("\\s+")

    /**
     * Turns arbitrary text (an artist or song title) into a single safe path segment:
     * no separators, no control characters, no leading dots, bounded length.
     */
    fun segment(name: String, maxBytes: Int = 120): String {
        val sb = StringBuilder(name.length)
        for (ch in name) {
            sb.append(
                when {
                    ch.isWhitespace() -> ' '
                    ch.code < 0x20 || ch.code == 0x7F || ch in ILLEGAL -> '_'
                    else -> ch
                },
            )
        }
        var s = sb.toString().replace(WHITESPACE, " ").trim()
        s = s.trimStart('.').trimEnd('.', ' ').trim()
        s = truncateUtf8(s, maxBytes).trimEnd('.', ' ')
        return if (s.isEmpty()) "_" else s
    }

    /** Builds "name.ext" keeping the extension intact when the name must be shortened. */
    fun fileName(baseName: String, extension: String, maxBytes: Int = 150): String {
        val ext = extension.filter { it.isLetterOrDigit() }.lowercase()
        val budget = (maxBytes - ext.length - 1).coerceAtLeast(16)
        val base = segment(baseName, budget)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /**
     * Normalises an archive entry name to a relative forward-slash path, or returns null when
     * the entry tries to escape the extraction root (ZIP-slip): absolute paths, drive letters,
     * ".." components or embedded NUL characters.
     */
    fun normalizeEntryPath(rawName: String): String? {
        if (rawName.indexOf('\u0000') >= 0) return null
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/")) return null
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return null
        val parts = ArrayList<String>()
        for (part in name.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> return null
                else -> parts.add(part)
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString("/")
    }

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + len > maxBytes) break
            sb.appendCodePoint(cp)
            bytes += len
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
