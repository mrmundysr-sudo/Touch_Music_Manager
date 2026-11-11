package com.mundymorning.touchmusic.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mundymorning.touchmusic.data.TrackEntity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class QueueEntry(val index: Int, val trackId: Long?, val title: String, val artist: String)

data class PlayerState(
    val connected: Boolean = false,
    val trackId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val currentIndex: Int = 0,
    val queue: List<QueueEntry> = emptyList(),
    /** Bumped on seeks and track changes so position displays refresh immediately. */
    val positionVersion: Int = 0,
) {
    val hasTrack: Boolean get() = queue.isNotEmpty()
}

/** The UI's handle on the playback service (a Media3 MediaController). All calls are main-thread. */
class PlayerConnection(private val context: Context) {
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var pending: (() -> Unit)? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<String> = _errors

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(
                rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED),
                positionChanged = events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                ),
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            val name = controller?.currentMediaItem?.mediaMetadata?.title ?: "this track"
            val reason = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "the file is missing"
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "access to the file was lost"
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                -> "the format is not supported on this device"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "the file is damaged"
                else -> "playback error"
            }
            _errors.tryEmit("Can't play $name: $reason")
        }
    }

    fun connect() {
        if (controller != null || future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(listener)
                publish(rebuildQueue = true, positionChanged = true)
                pending?.invoke()
            } catch (e: Exception) {
                _errors.tryEmit("Could not start the playback service")
            } finally {
                pending = null
                if (future === f && controller == null) future = null
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        future = null
        _state.value = _state.value.copy(connected = false, isPlaying = false)
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
        } else {
            pending = { controller?.let(block) }
            connect()
        }
    }

    fun playTracks(tracks: List<TrackEntity>, startIndex: Int = 0, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        withController { c ->
            if (shuffle != null) c.shuffleModeEnabled = shuffle
            val start = if (shuffle == true) tracks.indices.random() else startIndex.coerceIn(0, tracks.lastIndex)
            c.setMediaItems(tracks.map { it.toMediaItem() }, start, 0L)
            c.prepare()
            c.play()
        }
    }

    fun playNext(track: TrackEntity) = withController { c ->
        if (c.mediaItemCount == 0) {
            c.setMediaItem(track.toMediaItem())
            c.prepare()
            c.play()
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
        }
    }

    fun addToQueue(tracks: List<TrackEntity>) = withController { c ->
        val empty = c.mediaItemCount == 0
        c.addMediaItems(tracks.map { it.toMediaItem() })
        if (empty) {
            c.prepare()
            c.play()
        }
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0L)
            c.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }

    fun previous() = withController { c ->
        if (c.currentPosition > 4000 || !c.hasPreviousMediaItem()) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playQueueIndex(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.prepare()
            c.play()
        }
    }

    fun removeFromQueue(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun clearQueue() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    fun position(): Long = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L

    private fun publish(rebuildQueue: Boolean, positionChanged: Boolean) {
        val c = controller ?: return
        val old = _state.value
        val queue = if (rebuildQueue || old.queue.size != c.mediaItemCount) {
            List(c.mediaItemCount) { i ->
                val item = c.getMediaItemAt(i)
                QueueEntry(i, item.mediaId.toLongOrNull(), item.mediaMetadata.title?.toString() ?: "", item.mediaMetadata.artist?.toString() ?: "")
            }
        } else {
            old.queue
        }
        val current = c.currentMediaItem
        val meta = current?.mediaMetadata
        val duration = c.duration
        _state.value = PlayerState(
            connected = true,
            trackId = current?.mediaId?.toLongOrNull(),
            title = meta?.title?.toString() ?: "",
            artist = meta?.artist?.toString() ?: "",
            album = meta?.albumTitle?.toString() ?: "",
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = if (duration > 0) duration else 0L,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            currentIndex = c.currentMediaItemIndex,
            queue = queue,
            positionVersion = if (positionChanged) old.positionVersion + 1 else old.positionVersion,
        )
    }

    private fun TrackEntity.toMediaItem(): MediaItem {
        val parsed = Uri.parse(uri)
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(parsed)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(parsed).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setAlbumArtist(albumArtist)
                    .build(),
            )
            .build()
    }
}
