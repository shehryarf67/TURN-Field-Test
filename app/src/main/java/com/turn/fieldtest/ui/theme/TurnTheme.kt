package com.turn.fieldtest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val TurnNavy = Color(0xFF071522)
val TurnNavyRaised = Color(0xFF102536)
val TurnCyan = Color(0xFF4FD8EB)
val TurnBlue = Color(0xFF4B87FF)
val TurnMint = Color(0xFF72E0B1)
val TurnAmber = Color(0xFFFFC568)
val TurnRed = Color(0xFFFF7C7C)
val TurnInk = Color(0xFF0F172A)
val TurnPaper = Color(0xFFF8FAFC)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF062C64),
    primaryContainer = Color(0xFF163F7A),
    onPrimaryContainer = Color(0xFFDCE8FF),
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
    surfaceContainerLowest = Color(0xFF06121C),
    surfaceContainerLow = Color(0xFF0D202F),
    surfaceContainer = Color(0xFF122A3B),
    surfaceContainerHigh = Color(0xFF193548),
    surfaceContainerHighest = Color(0xFF223C4D),
    onSurfaceVariant = Color(0xFFB8CAD5),
    error = TurnRed
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A5FD0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1ECFF),
    onPrimaryContainer = Color(0xFF12396E),
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
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F9),
    surfaceContainer = Color(0xFFEBF1FA),
    surfaceContainerHigh = Color(0xFFE5EDF8),
    surfaceContainerHighest = Color(0xFFDEE8F5),
    onSurfaceVariant = Color(0xFF465A66),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFBA1A1A)
)

@Composable
fun TurnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content
    )
}
