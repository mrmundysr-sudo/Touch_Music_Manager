package com.mundymorning.touchmusic

import android.content.Context
import com.mundymorning.touchmusic.data.AppDatabase
import com.mundymorning.touchmusic.data.LibraryRepository
import com.mundymorning.touchmusic.importer.ImportController
import com.mundymorning.touchmusic.importer.LibraryScanner
import com.mundymorning.touchmusic.playback.PlayerConnection
import com.mundymorning.touchmusic.ui.ArtworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Manual dependency container. Each piece sits behind its own class so future sources
 * (home server, network libraries, sync, casting) can be added without reshaping the app.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Outlives screens: scans and edits keep running when the user navigates away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val db = AppDatabase.create(appContext)
    val repo = LibraryRepository(db)
    val artwork = ArtworkLoader(appContext, repo)
    val player = PlayerConnection(appContext)
    val importer = ImportController(appContext, repo) { artwork.reset() }
    val scanner = LibraryScanner(appContext, repo) { artwork.reset() }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages

    fun toast(message: String) {
        _messages.tryEmit(message)
    }
}
