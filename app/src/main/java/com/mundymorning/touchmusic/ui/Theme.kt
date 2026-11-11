package com.mundymorning.touchmusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** Temporary Mundy Morning Studios palette: night sky with a sunrise accent. Always dark. */
private val Colors = darkColorScheme(
    primary = Color(0xFFFFB347),
    onPrimary = Color(0xFF2A1800),
    primaryContainer = Color(0xFF4A3410),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFFF7A59),
    onSecondary = Color(0xFF2B0C03),
    secondaryContainer = Color(0xFF4A2015),
    onSecondaryContainer = Color(0xFFFFDAD0),
    tertiary = Color(0xFF7FD1C7),
    onTertiary = Color(0xFF00201C),
    background = Color(0xFF0D0E12),
    onBackground = Color(0xFFECE9E4),
    surface = Color(0xFF0D0E12),
    onSurface = Color(0xFFECE9E4),
    surfaceVariant = Color(0xFF1E2128),
    onSurfaceVariant = Color(0xFFA9ABB3),
    surfaceContainerLowest = Color(0xFF090A0D),
    surfaceContainerLow = Color(0xFF121418),
    surfaceContainer = Color(0xFF16181D),
    surfaceContainerHigh = Color(0xFF1E2128),
    surfaceContainerHighest = Color(0xFF262A33),
    outline = Color(0xFF4A4E59),
    outlineVariant = Color(0xFF2A2D35),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0000),
)

@Composable
fun TouchMusicTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val typography = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = Colors, typography = typography, content = content)
}
