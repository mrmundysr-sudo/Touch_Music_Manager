package com.mundymorning.touchmusic.core

import java.util.Locale

/** Knowledge about which files are audio the app can import and play. */
object AudioFormats {
    /** Extensions Android's media stack (Media3/ExoPlayer) can normally play. */
    val SUPPORTED: Set<String> = setOf(
        "mp3", "flac", "m4a", "m4b", "aac", "wav", "ogg", "oga", "opus", "mka", "amr", "3ga",
    )

    /** Audio formats we recognise but Android cannot play; reported instead of silently ignored. */
    val KNOWN_UNSUPPORTED: Set<String> = setOf(
        "wma", "aiff", "aif", "ape", "wv", "dsf", "dff", "mpc", "ra", "mid", "midi", "ac3", "dts", "tta", "au",
    )

    fun extensionOf(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        return if (dot <= 0 || dot == base.length - 1) "" else base.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    fun isSupportedAudio(extension: String): Boolean = extension.lowercase(Locale.ROOT) in SUPPORTED
    fun isKnownUnsupportedAudio(extension: String): Boolean = extension.lowercase(Locale.ROOT) in KNOWN_UNSUPPORTED
    fun isZip(extension: String): Boolean = extension.equals("zip", ignoreCase = true)

    fun mimeTypeFor(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "m4b" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/x-wav"
        "ogg", "oga", "opus" -> "audio/ogg"
        "mka" -> "audio/x-matroska"
        "amr" -> "audio/amr"
        "3ga" -> "audio/3gpp"
        else -> "audio/mpeg"
    }

    enum class Sniff { AUDIO, NOT_AUDIO, UNKNOWN }

    /**
     * Cheap magic-number check on the first bytes of a file. Only says NOT_AUDIO when the
     * header clearly belongs to another well-known file type (so a renamed .exe/.zip/.html
     * is rejected); anything unfamiliar is UNKNOWN and left to the platform decoder to judge.
     */
    fun sniff(header: ByteArray, length: Int): Sniff {
        if (length < 4) return Sniff.UNKNOWN
        fun at(i: Int) = header[i].toInt() and 0xFF
        fun starts(vararg bytes: Int): Boolean {
            if (length < bytes.size) return false
            for (i in bytes.indices) if (at(i) != bytes[i]) return false
            return true
        }
        fun startsAscii(s: String) = starts(*s.map { it.code }.toIntArray())

        if (startsAscii("ID3") || startsAscii("fLaC") || startsAscii("OggS") || startsAscii("RIFF") ||
            startsAscii("#!AMR") || starts(0x1A, 0x45, 0xDF, 0xA3)
        ) return Sniff.AUDIO
        if (length >= 8 && at(4) == 'f'.code && at(5) == 't'.code && at(6) == 'y'.code && at(7) == 'p'.code) return Sniff.AUDIO

        if (starts(0x50, 0x4B, 0x03, 0x04) || startsAscii("MZ") && at(2) == 0x90 || startsAscii("%PDF") ||
            starts(0x7F, 'E'.code, 'L'.code, 'F'.code) || startsAscii("Rar!") || starts(0x37, 0x7A, 0xBC, 0xAF) ||
            starts(0x89, 'P'.code, 'N'.code, 'G'.code) || starts(0xFF, 0xD8, 0xFF) || startsAscii("GIF8") ||
            startsAscii("<htm") || startsAscii("<HTM") || startsAscii("<!DO") || startsAscii("<!do") || startsAscii("<?xm")
        ) return Sniff.NOT_AUDIO

        // MPEG audio / ADTS frame sync: eleven set bits.
        if (at(0) == 0xFF && (at(1) and 0xE0) == 0xE0) return Sniff.AUDIO
        return Sniff.UNKNOWN
    }
}
