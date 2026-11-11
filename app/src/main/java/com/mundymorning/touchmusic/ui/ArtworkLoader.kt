package com.mundymorning.touchmusic.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mundymorning.touchmusic.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Loads album artwork from the tags embedded in an album's first track. Results are cached
 * in memory and as small JPEGs in the app's cache directory.
 */
class ArtworkLoader(private val context: Context, private val repo: LibraryRepository) {
    private val memory = LruCache<Long, ImageBitmap>(80)
    private val misses: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    private val dir = File(context.cacheDir, "artwork").apply { mkdirs() }
    private val gate = Semaphore(3)

    fun cached(albumId: Long): ImageBitmap? = memory.get(albumId)

    /** Forget "no artwork" results, e.g. after an import or rescan. */
    fun reset() = misses.clear()

    suspend fun load(albumId: Long): ImageBitmap? {
        memory.get(albumId)?.let { return it }
        if (albumId in misses) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                memory.get(albumId)?.let { return@withPermit it }
                val bitmap = try {
                    fromDisk(albumId) ?: extract(albumId)
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
                if (bitmap == null) {
                    misses.add(albumId)
                    null
                } else {
                    bitmap.asImageBitmap().also { memory.put(albumId, it) }
                }
            }
        }
    }

    private fun file(albumId: Long) = File(dir, "album_$albumId.jpg")

    private fun fromDisk(albumId: Long): Bitmap? {
        val f = file(albumId)
        return if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.path) else null
    }

    private suspend fun extract(albumId: Long): Bitmap? {
        val track = repo.tracks.firstOfAlbum(albumId) ?: return null
        val uri = Uri.parse(track.uri)
        var bitmap: Bitmap? = null
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            mmr.embeddedPicture?.let { bytes -> bitmap = decodeSampled(bytes, TARGET) }
        } catch (e: Exception) {
            // no embedded picture or file unreadable
        } finally {
            try {
                mmr.release()
            } catch (e: Exception) {
            }
        }
        if (bitmap == null && uri.authority == "media") {
            bitmap = try {
                context.contentResolver.loadThumbnail(uri, Size(TARGET, TARGET), null)
            } catch (e: Exception) {
                null
            }
        }
        bitmap?.let { b ->
            try {
                file(albumId).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            } catch (e: Exception) {
            }
        }
        return bitmap
    }

    private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val TARGET = 600
    }
}
