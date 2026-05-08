package com.audiorouter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Stack theme — glass + electric cyan accent on cool charcoal.
 *
 * Use [StackTheme] instead of [AppTheme] when rendering [MainWindowStack].
 * The original orange theme is left intact for the legacy [MainWindow].
 */

// Accent — luminous cyan (oklch 0.84 0.15 195)
val Cyan500 = Color(0xFF4FD7E0)
val Cyan400 = Color(0xFF7CE3EA)
val Cyan300 = Color(0xFFA8EEF3)

// Cool charcoal background ramp (slightly blue-tinted)
val StackBg = Color(0xFF0E1218)
val StackBgRaised = Color(0xFF141923)
val StackSurface = Color(0xFF1A2030)
val StackSurfaceHover = Color(0xFF222A3C)

// Subtle glass strokes / fills (use these directly with .background())
val Glass1 = Color(0x0AFFFFFF)   // 4% white — primary glass fill top
val Glass2 = Color(0x05FFFFFF)   // 2% white — primary glass fill bottom
val Glass3 = Color(0x14FFFFFF)   // 8% — hovered fill
val GlassStroke = Color(0x14FFFFFF)
val GlassStrokeStrong = Color(0x29FFFFFF)

// Per-channel hue palette (matches the design canvas)
val HueMaster = Color(0xFF4FD7E0)  // cyan
val HueGame   = Color(0xFFC97AE8)  // violet
val HueChat   = Color(0xFF7DE0A0)  // mint
val HueMedia  = Color(0xFFE8B27D)  // amber
val HueAux    = Color(0xFF8E9DE8)  // periwinkle
val HueMic    = Color(0xFFE87D8E)  // coral

// Mute / peak warning
val PeakHot   = Color(0xFFE87D5A)
val MuteRed   = Color(0xFFE85A6E)

// Text
val TextHi   = Color(0xFFE6EDF3)
val TextMid  = Color(0xCCE6EDF3)
val TextDim  = Color(0x80E6EDF3)
val TextFaint = Color(0x4DE6EDF3)

// Vertical glass fill for cards: top→bottom
val GlassFill: Brush
    get() = Brush.verticalGradient(listOf(Glass1, Glass2))

// Background gradient for the whole window
val StackBackdrop: Brush
    get() = Brush.radialGradient(
        colors = listOf(Color(0xFF1A2233), StackBg),
        radius = 1400f
    )

private val stackColorScheme = darkColorScheme(
    primary = Cyan500,
    onPrimary = Color(0xFF002328),
    primaryContainer = Color(0xFF003F47),
    onPrimaryContainer = Cyan300,
    secondary = Cyan400,
    onSecondary = Color.Black,
    background = StackBg,
    onBackground = TextHi,
    surface = StackSurface,
    onSurface = TextHi,
    surfaceVariant = StackSurfaceHover,
    onSurfaceVariant = TextDim,
    outline = GlassStroke,
    error = MuteRed
)

@Composable
fun StackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = stackColorScheme,
        content = content
    )
}
