package com.mundymorning.touchmusic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.mundymorning.touchmusic.AppContainer
import com.mundymorning.touchmusic.data.TrackEdit
import com.mundymorning.touchmusic.data.TrackEntity
import kotlinx.coroutines.launch

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalNav = staticCompositionLocalOf<NavHostController> { error("NavController not provided") }
val LocalPlayingTrackId = compositionLocalOf<Long?> { null }

fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val PLACEHOLDER_PALETTE = listOf(
    Color(0xFF5B3A1E) to Color(0xFF2A1B10),
    Color(0xFF5A2B2B) to Color(0xFF241214),
    Color(0xFF1F4A48) to Color(0xFF0F2222),
    Color(0xFF2E3A5F) to Color(0xFF141A2B),
    Color(0xFF4A2E5A) to Color(0xFF1F1426),
    Color(0xFF3F4A22) to Color(0xFF1B2010),
)

@Composable
fun AlbumArt(albumId: Long?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val loader = LocalContainer.current.artwork
    val bitmap by produceState<ImageBitmap?>(initialValue = albumId?.let { loader.cached(it) }, albumId) {
        value = if (albumId == null) null else loader.load(albumId)
    }
    val colors = PLACEHOLDER_PALETTE[((albumId ?: 0L) % PLACEHOLDER_PALETTE.size).toInt().let { if (it < 0) -it else it }]
    Box(
        modifier.clip(shape).background(Brush.linearGradient(listOf(colors.first, colors.second))),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(bitmap = b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.fillMaxSize(0.45f))
        }
    }
}

@Composable
fun ScreenTitle(text: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
        }
        Text(
            text,
            style = if (onBack != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp))
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Play")
        }
        FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Shuffle")
        }
    }
}

/** One song in a list, with the overflow menu shared by every list in the app. */
@Composable
fun TrackRow(
    track: TrackEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingNumber: Int? = null,
    showNumber: Boolean = false,
    extraMenu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val playing = LocalPlayingTrackId.current == track.id
    var menu by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNumber) {
            Text(
                leadingNumber?.toString() ?: "–",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(32.dp),
            )
        } else {
            AlbumArt(track.albumId, Modifier.size(48.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (showNumber) track.artist else "${track.artist} • ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.needsReview) {
            Icon(Icons.Rounded.Warning, contentDescription = "Needs review", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
        if (track.favorite) {
            Icon(Icons.Rounded.Favorite, contentDescription = "Favorite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
        Text(
            formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; c.player.playNext(track) })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; c.player.addToQueue(listOf(track)) })
                DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { menu = false; showPlaylists = true })
                DropdownMenuItem(
                    text = { Text(if (track.favorite) "Remove from favorites" else "Add to favorites") },
                    onClick = {
                        menu = false
                        c.appScope.launch { c.repo.tracks.setFavorite(track.id, !track.favorite) }
                    },
                )
                DropdownMenuItem(text = { Text("Go to album") }, onClick = { menu = false; nav.navigate("album/${track.albumId}") })
                DropdownMenuItem(text = { Text("Go to artist") }, onClick = { menu = false; nav.navigate("artist/${track.artistId}") })
                DropdownMenuItem(text = { Text("Edit details…") }, onClick = { menu = false; showEdit = true })
                extraMenu?.invoke { menu = false }
                DropdownMenuItem(
                    text = { Text("Remove from library") },
                    onClick = {
                        menu = false
                        c.appScope.launch {
                            c.repo.removeTrack(track.id)
                            c.toast("Removed from the library. The file itself was not deleted.")
                        }
                    },
                )
            }
        }
    }
    if (showPlaylists) AddToPlaylistDialog(track) { showPlaylists = false }
    if (showEdit) EditTrackDialog(track) { showEdit = false }
}

@Composable
fun AddToPlaylistDialog(track: TrackEntity, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val playlists by remember { c.repo.playlists.summaries() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var newName by remember { mutableStateOf("") }

    fun add(playlistId: Long, name: String) {
        c.appScope.launch {
            val added = c.repo.addToPlaylist(playlistId, track.id)
            c.toast(if (added) "Added to $name" else "Already in $name")
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                playlists.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { add(p.id, p.name) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.trackCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New playlist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = newName.isNotBlank(),
                onClick = {
                    val name = newName.trim()
                    c.appScope.launch {
                        val id = c.repo.createPlaylist(name)
                        c.repo.addToPlaylist(id, track.id)
                        c.toast("Added to $name")
                    }
                    onDismiss()
                },
            ) { Text("Create and add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EditTrackDialog(track: TrackEntity, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(if (track.needsReview && track.artist == "Unknown Artist") "" else track.artist) }
    var albumArtist by remember { mutableStateOf(if (track.albumArtist == track.artist) "" else track.albumArtist) }
    var album by remember { mutableStateOf(if (track.album == "Unknown Album") "" else track.album) }
    var trackNo by remember { mutableStateOf(track.trackNumber?.toString() ?: "") }
    var discNo by remember { mutableStateOf(track.discNumber?.toString() ?: "") }
    var year by remember { mutableStateOf(track.year?.toString() ?: "") }
    var genre by remember { mutableStateOf(track.genre ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "File: ${track.folder}/${track.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                track.reviewReason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    albumArtist, { albumArtist = it },
                    label = { Text("Album artist (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
                    OutlinedTextField(trackNo, { trackNo = it.filter(Char::isDigit).take(3) }, label = { Text("Track") }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f))
                    OutlinedTextField(discNo, { discNo = it.filter(Char::isDigit).take(2) }, label = { Text("Disc") }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f))
                    OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Year") }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1.3f))
                }
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && artist.isNotBlank(),
                onClick = {
                    val edit = TrackEdit(
                        title = title, artist = artist, albumArtist = albumArtist,
                        album = album.ifBlank { "Unknown Album" },
                        trackNumber = trackNo.toIntOrNull()?.takeIf { it > 0 },
                        discNumber = discNo.toIntOrNull()?.takeIf { it > 0 },
                        year = year.toIntOrNull()?.takeIf { it in 1000..2999 },
                        genre = genre,
                    )
                    c.appScope.launch {
                        c.scanner.applyEdit(track, edit)
                        c.toast("Saved")
                    }
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextPromptDialog(title: String, label: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
