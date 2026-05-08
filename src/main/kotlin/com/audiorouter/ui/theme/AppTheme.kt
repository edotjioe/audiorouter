package com.audiorouter.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Orange500 = Color(0xFFFF6B00)
val Orange300 = Color(0xFFFF9A4D)
val DarkBg = Color(0xFF1A1A1A)
val SurfaceDark = Color(0xFF252525)
val CardDark = Color(0xFF2F2F2F)
val DividerDark = Color(0xFF3A3A3A)
val TextPrimary = Color(0xFFEEEEEE)
val TextSecondary = Color(0xFF999999)

private val darkColorScheme = darkColorScheme(
    primary = Orange500,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A2000),
    onPrimaryContainer = Orange300,
    secondary = Orange300,
    onSecondary = Color.Black,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextSecondary,
    outline = DividerDark,
    error = Color(0xFFCF6679)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}
