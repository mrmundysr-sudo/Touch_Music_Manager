package com.mundymorning.touchmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundymorning.touchmusic.data.AlbumEntity
import com.mundymorning.touchmusic.data.ArtistEntity
import com.mundymorning.touchmusic.data.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Home

@Composable
fun HomeScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val count by remember { c.repo.tracks.count() }.collectAsStateWithLifecycle(initialValue = -1)
    val recentlyPlayed by remember { c.repo.tracks.recentlyPlayed(15) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val recentAlbums by remember { c.repo.albums.recentlyAdded(15) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val favorites by remember { c.repo.tracks.favorites() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val review by remember { c.repo.tracks.needsReview() }.collectAsStateWithLifecycle(initialValue = emptyList())

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)) {
                Text("Touch Music", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (count > 0) "$count songs in your library" else "Your personal offline library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (count == 0) {
            item {
                EmptyState(
                    Icons.Rounded.LibraryMusic,
                    "Your library is empty",
                    "Scan this phone for music, pick a folder, or import ZIP archives. Everything stays on this device.",
                    "Add music",
                ) { nav.navigate("import") }
            }
        } else if (count > 0) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickTile("Shuffle all", Icons.Rounded.Shuffle, Modifier.weight(1f)) {
                        c.appScope.launch { c.player.playTracks(c.repo.tracks.songs().first(), shuffle = true) }
                    }
                    QuickTile("Favorites", Icons.Rounded.Favorite, Modifier.weight(1f)) { nav.navigate("favorites") }
                }
            }
            if (review.isNotEmpty()) {
                item {
                    Row(
                        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer).clickable { nav.navigate("review") }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${review.size} songs need review",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Text("Fix", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (recentlyPlayed.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(recentlyPlayed, key = { _, t -> t.id }) { index, t ->
                            ArtCard(t.albumId, t.title, t.artist) { c.player.playTracks(recentlyPlayed, index) }
                        }
                    }
                }
            }
            if (recentAlbums.isNotEmpty()) {
                item { SectionHeader("Recently added") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(recentAlbums, key = { it.id }) { a ->
                            ArtCard(a.id, a.title, a.artistName) { nav.navigate("album/${a.id}") }
                        }
                    }
                }
            }
            if (favorites.isNotEmpty()) {
                item { SectionHeader("Favorites") }
                val shown = favorites.take(6)
                itemsIndexed(shown, key = { _, t -> "fav${t.id}" }) { index, t ->
                    TrackRow(t, onClick = { c.player.playTracks(favorites, index) })
                }
            }
        }
    }
}

@Composable
private fun QuickTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
private fun ArtCard(albumId: Long, title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick)) {
        AlbumArt(albumId, Modifier.size(132.dp), RoundedCornerShape(10.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --------------------------------------------------------------- Library

private val LIBRARY_TABS = listOf("Artists", "Albums", "Songs", "Playlists", "Recently Added", "Needs Review")

@Composable
fun LibraryScreen() {
    val c = LocalContainer.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Library")
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 12.dp,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {},
        ) {
            LIBRARY_TABS.forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(title, maxLines = 1) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (tab) {
            0 -> ArtistList(remember { c.repo.artists.all() })
            1 -> AlbumGrid(remember { c.repo.albums.all() })
            2 -> SongList(remember { c.repo.tracks.songs() }, "No songs yet", "Use the Import tab to add music.")
            3 -> PlaylistList()
            4 -> SongList(remember { c.repo.tracks.recentlyAdded(200) }, "Nothing added yet", "Newly imported songs appear here.")
            else -> NeedsReviewList()
        }
    }
}

@Composable
fun SongList(source: Flow<List<TrackEntity>>, emptyTitle: String, emptyBody: String, header: (@Composable () -> Unit)? = null) {
    val c = LocalContainer.current
    val tracks by source.collectAsStateWithLifecycle(initialValue = null)
    val list = tracks ?: return
    if (list.isEmpty()) {
        EmptyState(Icons.Rounded.MusicNote, emptyTitle, emptyBody)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (header != null) item { header() }
        item {
            PlayButtons(onPlay = { c.player.playTracks(list, 0, shuffle = false) }, onShuffle = { c.player.playTracks(list, shuffle = true) })
        }
        itemsIndexed(list, key = { _, t -> t.id }) { index, t ->
            TrackRow(t, onClick = { c.player.playTracks(list, index) })
        }
    }
}

@Composable
private fun NeedsReviewList() {
    val c = LocalContainer.current
    SongList(
        remember { c.repo.tracks.needsReview() },
        "Nothing needs review",
        "Songs whose artist or title could not be determined with confidence are listed here.",
    ) {
        Text(
            "These songs are missing reliable artist information. Nothing was guessed: open the ⋮ menu and choose “Edit details” to file them properly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun NeedsReviewScreen() {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Needs Review", onBack = { nav.popBackStack() })
        NeedsReviewList()
    }
}

@Composable
fun FavoritesScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Favorites", onBack = { nav.popBackStack() })
        SongList(remember { c.repo.tracks.favorites() }, "No favorites yet", "Tap the heart on the Now Playing screen, or use a song's ⋮ menu.")
    }
}

@Composable
private fun ArtistList(source: Flow<List<ArtistEntity>>) {
    val nav = LocalNav.current
    val artists by source.collectAsStateWithLifecycle(initialValue = null)
    val list = artists ?: return
    if (list.isEmpty()) {
        EmptyState(Icons.Rounded.Person, "No artists yet", "Use the Import tab to add music.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(list, key = { it.id }) { a -> ArtistRow(a) { nav.navigate("artist/${a.id}") } }
    }
}

@Composable
fun ArtistRow(artist: ArtistEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AlbumGrid(source: Flow<List<AlbumEntity>>) {
    val nav = LocalNav.current
    val albums by source.collectAsStateWithLifecycle(initialValue = null)
    val list = albums ?: return
    if (list.isEmpty()) {
        EmptyState(Icons.Rounded.Album, "No albums yet", "Use the Import tab to add music.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(list, key = { it.id }) { a -> AlbumCard(a) { nav.navigate("album/${a.id}") } }
    }
}

@Composable
fun AlbumCard(album: AlbumEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        AlbumArt(album.id, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(10.dp))
        Spacer(Modifier.height(8.dp))
        Text(album.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artistName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --------------------------------------------------------- Album / Artist

@Composable
fun AlbumScreen(albumId: Long) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val album by remember(albumId) { c.repo.albums.observe(albumId) }.collectAsStateWithLifecycle(initialValue = null)
    val tracks by remember(albumId) { c.repo.tracks.albumTracks(albumId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val multiDisc = tracks.any { (it.discNumber ?: 1) > 1 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { ScreenTitle("Album", onBack = { nav.popBackStack() }) }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(albumId, Modifier.size(132.dp), RoundedCornerShape(12.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(album?.title ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        album?.artistName ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = album != null) { album?.let { nav.navigate("artist/${it.artistId}") } },
                    )
                    val total = tracks.sumOf { it.durationMs }
                    Text(
                        listOfNotNull(album?.year?.toString(), "${tracks.size} songs", formatDuration(total).ifEmpty { null }).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            PlayButtons(onPlay = { c.player.playTracks(tracks, 0, shuffle = false) }, onShuffle = { c.player.playTracks(tracks, shuffle = true) })
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, t ->
            if (multiDisc && (index == 0 || (tracks[index - 1].discNumber ?: 1) != (t.discNumber ?: 1))) {
                Text(
                    "Disc ${t.discNumber ?: 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            TrackRow(t, onClick = { c.player.playTracks(tracks, index) }, leadingNumber = t.trackNumber, showNumber = true)
        }
    }
}

@Composable
fun ArtistScreen(artistId: Long) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val artist by remember(artistId) { c.repo.artists.observe(artistId) }.collectAsStateWithLifecycle(initialValue = null)
    val albums by remember(artistId) { c.repo.albums.byArtist(artistId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tracks by remember(artistId) { c.repo.tracks.artistTracks(artistId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { ScreenTitle(artist?.name ?: "Artist", onBack = { nav.popBackStack() }) }
        item {
            Text(
                "${albums.size} albums • ${tracks.size} songs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item {
            PlayButtons(onPlay = { c.player.playTracks(tracks, 0, shuffle = false) }, onShuffle = { c.player.playTracks(tracks, shuffle = true) })
        }
        if (albums.isNotEmpty()) {
            item { SectionHeader("Albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(albums, key = { it.id }) { a -> AlbumCard(a, Modifier.width(140.dp)) { nav.navigate("album/${a.id}") } }
                }
            }
        }
        item { SectionHeader("Songs") }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, t ->
            TrackRow(t, onClick = { c.player.playTracks(tracks, index) })
        }
    }
}

// ----------------------------------------------------------------- Search

@Composable
fun SearchScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    var query by rememberSaveable { mutableStateOf("") }
    val pattern = "%" + query.trim().replace("%", "").replace("_", " ") + "%"
    val active = query.trim().isNotEmpty()
    val songs by remember(pattern) { c.repo.tracks.search(pattern) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val albums by remember(pattern) { c.repo.albums.search(pattern) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val artists by remember(pattern) { c.repo.artists.search(pattern) }.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Search")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, albums, artists") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (!active) {
            EmptyState(Icons.Rounded.Search, "Find your music", "Search by song title, album or artist.")
        } else if (songs.isEmpty() && albums.isEmpty() && artists.isEmpty()) {
            EmptyState(Icons.Rounded.Search, "No results", "Nothing in your library matches “${query.trim()}”.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (artists.isNotEmpty()) {
                    item { SectionHeader("Artists") }
                    items(artists, key = { "ar${it.id}" }) { a -> ArtistRow(a) { nav.navigate("artist/${a.id}") } }
                }
                if (albums.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(albums, key = { it.id }) { a -> AlbumCard(a, Modifier.width(140.dp)) { nav.navigate("album/${a.id}") } }
                        }
                    }
                }
                if (songs.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    itemsIndexed(songs, key = { _, t -> "s${t.id}" }) { index, t ->
                        TrackRow(t, onClick = { c.player.playTracks(songs, index) })
                    }
                }
            }
        }
    }
}
