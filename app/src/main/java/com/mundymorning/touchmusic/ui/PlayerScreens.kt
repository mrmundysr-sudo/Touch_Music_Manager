package com.mundymorning.touchmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.mundymorning.touchmusic.playback.PlayerConnection
import com.mundymorning.touchmusic.playback.PlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Current playback position, refreshed twice a second while playing. */
@Composable
private fun playbackPosition(connection: PlayerConnection, state: PlayerState): Long {
    var position by remember { mutableLongStateOf(connection.position()) }
    LaunchedEffect(state.isPlaying, state.trackId, state.positionVersion) {
        position = connection.position()
        while (state.isPlaying) {
            delay(500)
            position = connection.position()
        }
    }
    return position
}

@Composable
fun MiniPlayer(state: PlayerState, onOpen: () -> Unit) {
    val c = LocalContainer.current
    val track by remember(state.trackId) { c.repo.tracks.observe(state.trackId ?: -1L) }.collectAsStateWithLifecycle(initialValue = null)
    val position = playbackPosition(c.player, state)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onOpen)) {
        LinearProgressIndicator(
            progress = { if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(track?.albumId, Modifier.size(44.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title.ifEmpty { "Unknown title" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { c.player.togglePlay() }) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (state.isPlaying) "Pause" else "Play", modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { c.player.next() }) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next") }
        }
    }
}

@Composable
fun NowPlayingScreen(state: PlayerState, onClose: () -> Unit, onOpenAlbum: (Long) -> Unit) {
    val c = LocalContainer.current
    val track by remember(state.trackId) { c.repo.tracks.observe(state.trackId ?: -1L) }.collectAsStateWithLifecycle(initialValue = null)
    var showQueue by remember { mutableStateOf(false) }
    val position = playbackPosition(c.player, state)
    var dragging by remember { mutableStateOf<Float?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", modifier = Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (showQueue) "PLAY QUEUE" else "NOW PLAYING", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.album.isNotEmpty()) {
                        Text(state.album, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(
                        Icons.Rounded.QueueMusic, contentDescription = "Queue",
                        tint = if (showQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (showQueue) {
                QueueList(state, Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AlbumArt(
                        track?.albumId,
                        Modifier.padding(vertical = 16.dp).aspectRatio(1f).clickable(enabled = track != null) {
                            track?.let { onOpenAlbum(it.albumId) }
                        },
                        RoundedCornerShape(16.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.title.ifEmpty { "Unknown title" }, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val t = track
                if (t != null) {
                    IconButton(onClick = { c.appScope.launch { c.repo.tracks.setFavorite(t.id, !t.favorite) } }) {
                        Icon(
                            if (t.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (t.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            val duration = state.durationMs.coerceAtLeast(1L).toFloat()
            val shown = dragging ?: position.toFloat().coerceIn(0f, duration)
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { c.player.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..duration,
                enabled = state.durationMs > 0,
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(shown.toLong()).ifEmpty { "0:00" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(state.durationMs).ifEmpty { "–:––" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { c.player.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { c.player.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
                }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { c.player.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                IconButton(onClick = { c.player.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { c.player.cycleRepeat() }) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Repeat",
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueList(state: PlayerState, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentIndex - 1).coerceAtLeast(0))
    LazyColumn(modifier.fillMaxWidth().padding(vertical = 8.dp), state = listState) {
        itemsIndexed(state.queue) { index, entry ->
            val current = index == state.currentIndex
            Row(
                Modifier.fillMaxWidth().clickable { c.player.playQueueIndex(index) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(entry.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!current) {
                    IconButton(onClick = { c.player.removeFromQueue(index) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove from queue", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
