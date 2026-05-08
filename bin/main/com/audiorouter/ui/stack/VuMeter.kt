package com.audiorouter.ui.stack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.audiorouter.ui.theme.Cyan400
import com.audiorouter.ui.theme.PeakHot
import kotlinx.coroutines.delay
import kotlin.math.log10
import kotlin.math.max

/**
 * Stereo VU meter displaying two horizontal bar rows driven by real RMS audio levels.
 *
 * [levelL] and [levelR] are 0–1 RMS values supplied by [LevelMonitor] at approximately 10 Hz.
 * Internally each row applies fast-attack / slow-decay smoothing and peak-hold at 30 Hz via
 * [useSmoothedLevel], keeping the animation fluid even when the source updates slowly.
 *
 * The bar is divided into [VuMeterRow.segments] colored segments:
 * - 0–60%: cyan (normal)
 * - 60–85%: yellow-green (elevated)
 * - 85–100%: orange-red (peak / hot)
 * A white peak-hold tick appears briefly after the highest recent level.
 *
 * @param levelL   Left-channel RMS level in 0–1.
 * @param levelR   Right-channel RMS level in 0–1.
 * @param width    Horizontal width of each bar row.
 * @param modifier Optional [Modifier] applied to the [Column] container.
 */
@Composable
fun VuMeterStereo(
    levelL: Float,
    levelR: Float,
    width: Dp,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        VuMeterRow(rawLevel = levelL, width = width)
        VuMeterRow(rawLevel = levelR, width = width)
    }
}

/**
 * A single horizontal VU meter bar with segment coloring and peak hold.
 *
 * @param rawLevel  Raw 0–1 RMS level from the audio backend.
 * @param width     Horizontal extent of the bar (used for segment layout math).
 * @param height    Height of the bar in dp.
 * @param segments  Number of colored LED-style segments.
 * @param modifier  Optional [Modifier].
 */
@Composable
fun VuMeterRow(
    rawLevel: Float,
    width: Dp,
    height: Dp = 4.dp,
    segments: Int = 32,
    modifier: Modifier = Modifier
) {
    // Convert linear RMS to perceptual dBFS scale: -60 dBFS → 0.0, 0 dBFS → 1.0
    val dBFS = 20f * log10(rawLevel.coerceAtLeast(1e-6f))
    val scaledLevel = ((dBFS + 60f) / 60f).coerceIn(0f, 1f)
    val (level, peak) = useSmoothedLevel(scaledLevel)
    Canvas(modifier = modifier.height(height)) {
        val segGap = 2f
        val totalGap = segGap * (segments - 1)
        val segW = max(1f, (size.width - totalGap) / segments)
        val lit = (level * segments).toInt().coerceIn(0, segments)
        val peakSeg = (peak * segments).toInt().coerceIn(0, segments)
        for (i in 0 until segments) {
            val t = i.toFloat() / (segments - 1).coerceAtLeast(1)
            val isLit = i < lit
            val isPeak = !isLit && i == peakSeg - 1 && peak > 0.02f
            val color: Color = when {
                isPeak       -> Color(0xFFD9F5F8)
                t < 0.60f   -> if (isLit) Cyan400          else Color(0x14FFFFFF)
                t < 0.85f   -> if (isLit) Color(0xFFC9E47C) else Color(0x14FFFFFF)
                else        -> if (isLit) PeakHot           else Color(0x14FFFFFF)
            }
            drawRect(color = color, topLeft = Offset(i * (segW + segGap), 0f), size = Size(segW, size.height))
        }
    }
}

/** Fast-attack, slow-decay smoothing + peak hold updated at ~30 Hz. */
@Composable
private fun useSmoothedLevel(rawLevel: Float): Pair<Float, Float> {
    val current by rememberUpdatedState(rawLevel)
    var level by remember { mutableStateOf(0f) }
    var peak  by remember { mutableStateOf(0f) }
    var peakHold by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            // Fast attack (0.7 weight on new), slow decay (0.05 step)
            level = if (current > level) level * 0.3f + current * 0.7f
                    else (level - 0.04f).coerceAtLeast(current)

            if (level >= peak) {
                peak = level
                peakHold = 0
            } else {
                peakHold++
                if (peakHold > 15) peak = (peak - 0.015f).coerceAtLeast(level)
            }
        }
    }
    return level to peak
}
