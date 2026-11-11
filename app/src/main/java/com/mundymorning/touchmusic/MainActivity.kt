package com.mundymorning.touchmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mundymorning.touchmusic.ui.TouchMusicRoot

class MainActivity : ComponentActivity() {
    private val container get() = (application as TouchMusicApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { TouchMusicRoot(container) }
    }

    override fun onStart() {
        super.onStart()
        container.player.connect()
    }

    override fun onDestroy() {
        // Keep the session across rotation and backgrounding; let go only when the app is closed.
        if (isFinishing) container.player.disconnect()
        super.onDestroy()
    }
}
