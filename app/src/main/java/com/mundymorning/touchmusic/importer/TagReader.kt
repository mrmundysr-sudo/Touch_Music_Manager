package com.mundymorning.touchmusic.importer

import android.media.MediaMetadataRetriever
import com.mundymorning.touchmusic.core.ProbeResult
import com.mundymorning.touchmusic.core.RawTags

/** Reads embedded tags with the platform decoder and reports whether the file is playable audio. */
object TagReader {
    private val YEAR = Regex("(19|20)\\d{2}")
    private val LEADING_INT = Regex("^\\s*(\\d{1,4})")

    fun leadingInt(value: String?): Int? = value?.let { LEADING_INT.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    fun year(value: String?): Int? = value?.let { YEAR.find(it)?.value?.toIntOrNull() }

    fun read(configure: (MediaMetadataRetriever) -> Unit): ProbeResult {
        val mmr = MediaMetadataRetriever()
        return try {
            configure(mmr)
            fun s(key: Int): String? = mmr.extractMetadata(key)
            val duration = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val hasAudio = s(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
            val playable = hasAudio || (duration ?: 0L) > 0L
            ProbeResult(
                playable,
                RawTags(
                    title = s(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    trackNumber = leadingInt(s(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                    discNumber = leadingInt(s(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                    genre = s(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    year = year(s(MediaMetadataRetriever.METADATA_KEY_YEAR)) ?: year(s(MediaMetadataRetriever.METADATA_KEY_DATE)),
                    durationMs = duration,
                    compilation = s(MediaMetadataRetriever.METADATA_KEY_COMPILATION)?.trim() == "1",
                ),
            )
        } catch (e: Exception) {
            ProbeResult(false, null)
        } finally {
            try {
                mmr.release()
            } catch (e: Exception) {
                // ignored
            }
        }
    }
}
