package com.mundymorning.touchmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mundymorning.touchmusic.AppContainer

private data class TopTab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TopTab("home", "Home", Icons.Rounded.Home),
    TopTab("library", "Library", Icons.Rounded.LibraryMusic),
    TopTab("search", "Search", Icons.Rounded.Search),
    TopTab("import", "Import", Icons.Rounded.Download),
    TopTab("playlists", "Playlists", Icons.Rounded.QueueMusic),
)

@Composable
fun TouchMusicRoot(container: AppContainer) {
    TouchMusicTheme {
        val nav = rememberNavController()
        val player by container.player.state.collectAsStateWithLifecycle()
        CompositionLocalProvider(
            LocalContainer provides container,
            LocalNav provides nav,
            LocalPlayingTrackId provides player.trackId,
        ) {
            var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { container.player.errors.collect { snackbar.showSnackbar(it) } }
            LaunchedEffect(Unit) { container.messages.collect { snackbar.showSnackbar(it) } }
            val backStack by nav.currentBackStackEntryAsState()
            val route = backStack?.destination?.route ?: "home"

            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        Column {
                            if (player.hasTrack) MiniPlayer(player, onOpen = { nowPlayingOpen = true })
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                                TABS.forEach { tab ->
                                    NavigationBarItem(
                                        selected = route == tab.route,
                                        onClick = {
                                            nav.navigate(tab.route) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = { Text(tab.label, maxLines = 1) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                        composable("home") { HomeScreen() }
                        composable("library") { LibraryScreen() }
                        composable("search") { SearchScreen() }
                        composable("import") { ImportScreen() }
                        composable("playlists") { PlaylistsScreen() }
                        composable("favorites") { FavoritesScreen() }
                        composable("review") { NeedsReviewScreen() }
                        composable("album/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            AlbumScreen(entry.arguments?.getLong("id") ?: 0L)
                        }
                        composable("artist/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            ArtistScreen(entry.arguments?.getLong("id") ?: 0L)
                        }
                        composable("playlist/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            PlaylistScreen(entry.arguments?.getLong("id") ?: 0L)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = nowPlayingOpen && player.hasTrack,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it }),
                ) {
                    NowPlayingScreen(
                        state = player,
                        onClose = { nowPlayingOpen = false },
                        onOpenAlbum = { albumId ->
                            nowPlayingOpen = false
                            nav.navigate("album/$albumId")
                        },
                    )
                }
            }
            BackHandler(enabled = nowPlayingOpen) { nowPlayingOpen = false }
        }
    }
}
