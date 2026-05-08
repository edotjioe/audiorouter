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
import kotlin.math.max

/**
 * Stereo VU meter driven by real RMS levels from [LevelMonitor].
 *
 * [levelL] and [levelR] are 0..1 RMS values updated ~10 Hz by the monitor.
 * The composable applies fast-attack / slow-decay smoothing and a peak-hold
 * tick internally at 30 Hz so the animation stays fluid.
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

@Composable
fun VuMeterRow(
    rawLevel: Float,
    width: Dp,
    height: Dp = 4.dp,
    segments: Int = 32,
    modifier: Modifier = Modifier
) {
    val (level, peak) = useSmoothedLevel(rawLevel)
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
