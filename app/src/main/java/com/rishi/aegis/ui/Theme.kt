package com.rishi.aegis.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Terminal / security palette
val AegisGreen = Color(0xFF38E08A)
val AegisCyan = Color(0xFF33C4E0)
val AegisBg = Color(0xFF0B0F14)
val AegisSurface = Color(0xFF121821)
val AegisSurfaceVariant = Color(0xFF1B2531)
val AegisOnSurface = Color(0xFFE6EDF3)
val AegisMuted = Color(0xFF8B98A9)
val AegisError = Color(0xFFFF6B6B)
val AegisWarn = Color(0xFFF2C94C)

private val AegisColors = darkColorScheme(
    primary = AegisGreen,
    onPrimary = Color(0xFF06210F),
    primaryContainer = Color(0xFF10351F),
    onPrimaryContainer = AegisGreen,
    secondary = AegisCyan,
    onSecondary = Color(0xFF042028),
    background = AegisBg,
    onBackground = AegisOnSurface,
    surface = AegisSurface,
    onSurface = AegisOnSurface,
    surfaceVariant = AegisSurfaceVariant,
    onSurfaceVariant = AegisMuted,
    error = AegisError,
    onError = Color(0xFF2A0A0A),
    outline = Color(0xFF2A3646),
)

@Composable
fun AegisTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme() // app is dark regardless
    MaterialTheme(
        colorScheme = AegisColors,
        typography = Typography(),
        content = content,
    )
}
