package com.turn.fieldtest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TurnNavy = Color(0xFF071522)
val TurnNavyRaised = Color(0xFF102536)
val TurnCyan = Color(0xFF4FD8EB)
val TurnBlue = Color(0xFF4B87FF)
val TurnMint = Color(0xFF72E0B1)
val TurnAmber = Color(0xFFFFC568)
val TurnRed = Color(0xFFFF7C7C)
val TurnInk = Color(0xFF142432)
val TurnPaper = Color(0xFFF5F8FA)

private val DarkColors = darkColorScheme(
    primary = TurnCyan,
    onPrimary = Color(0xFF002F36),
    primaryContainer = Color(0xFF0A4651),
    onPrimaryContainer = Color(0xFF9EF0FA),
    secondary = TurnBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF173D74),
    onSecondaryContainer = Color(0xFFD8E6FF),
    tertiary = TurnMint,
    onTertiary = Color(0xFF073827),
    background = TurnNavy,
    onBackground = Color(0xFFE3EDF3),
    surface = Color(0xFF0B1C29),
    onSurface = Color(0xFFE3EDF3),
    surfaceVariant = TurnNavyRaised,
    onSurfaceVariant = Color(0xFFB8CAD5),
    error = TurnRed
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006777),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA4EFF8),
    onPrimaryContainer = Color(0xFF002F37),
    secondary = Color(0xFF245DA8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E6FF),
    onSecondaryContainer = Color(0xFF0C2C57),
    tertiary = Color(0xFF176B4D),
    onTertiary = Color.White,
    background = TurnPaper,
    onBackground = TurnInk,
    surface = Color.White,
    onSurface = TurnInk,
    surfaceVariant = Color(0xFFE6EEF2),
    onSurfaceVariant = Color(0xFF465A66),
    error = Color(0xFFBA1A1A)
)

@Composable
fun TurnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
