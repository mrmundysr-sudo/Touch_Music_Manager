package com.mundymorning.touchmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen() {
    var creating by remember { mutableStateOf(false) }
    val c = LocalContainer.current
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Playlists") {
            IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, contentDescription = "New playlist") }
        }
        PlaylistList()
    }
    if (creating) {
        TextPromptDialog("New playlist", "Name", "", "Create", onConfirm = { name ->
            creating = false
            c.appScope.launch { c.repo.createPlaylist(name) }
        }, onDismiss = { creating = false })
    }
}

@Composable
fun PlaylistList() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val playlists by remember { c.repo.playlists.summaries() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val favorites by remember { c.repo.tracks.favorites() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            PlaylistRow("Favorites", "${favorites.size} songs", Icons.Rounded.Favorite) { nav.navigate("favorites") }
        }
        items(playlists, key = { it.id }) { p ->
            PlaylistRow(p.name, "${p.trackCount} songs", Icons.Rounded.QueueMusic) { nav.navigate("playlist/${p.id}") }
        }
        item {
            PlaylistRow("New playlist", "Create a custom playlist", Icons.Rounded.Add) { creating = true }
        }
    }
    if (creating) {
        TextPromptDialog("New playlist", "Name", "", "Create", onConfirm = { name ->
            creating = false
            c.appScope.launch { c.repo.createPlaylist(name) }
        }, onDismiss = { creating = false })
    }
}

@Composable
private fun PlaylistRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PlaylistScreen(playlistId: Long) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val playlist by remember(playlistId) { c.repo.playlists.observe(playlistId) }.collectAsStateWithLifecycle(initialValue = null)
    val allTracks by remember(playlistId) { c.repo.playlists.tracks(playlistId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tracks = allTracks.filter { !it.missing }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            ScreenTitle(playlist?.name ?: "Playlist", onBack = { nav.popBackStack() }) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Playlist options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Add all to queue") }, onClick = { menu = false; c.player.addToQueue(tracks) })
                        DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; deleting = true })
                    }
                }
            }
        }
        item {
            val missing = allTracks.size - tracks.size
            Text(
                "${tracks.size} songs" + if (missing > 0) " • $missing unavailable" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (tracks.isEmpty()) {
            item {
                EmptyState(Icons.Rounded.QueueMusic, "This playlist is empty", "Open any song's ⋮ menu and choose “Add to playlist”.")
            }
        } else {
            item {
                PlayButtons(onPlay = { c.player.playTracks(tracks, 0, shuffle = false) }, onShuffle = { c.player.playTracks(tracks, shuffle = true) })
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, t ->
                TrackRow(t, onClick = { c.player.playTracks(tracks, index) }, extraMenu = { dismiss ->
                    DropdownMenuItem(
                        text = { Text("Remove from this playlist") },
                        onClick = {
                            dismiss()
                            c.appScope.launch { c.repo.playlists.removeTrack(playlistId, t.id) }
                        },
                    )
                })
            }
        }
    }

    if (renaming) {
        TextPromptDialog("Rename playlist", "Name", playlist?.name ?: "", "Rename", onConfirm = { name ->
            renaming = false
            c.appScope.launch { c.repo.playlists.rename(playlistId, name) }
        }, onDismiss = { renaming = false })
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete playlist?") },
            text = { Text("“${playlist?.name ?: ""}” will be deleted. The songs stay in your library.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    c.appScope.launch { c.repo.playlists.delete(playlistId) }
                    nav.popBackStack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}
