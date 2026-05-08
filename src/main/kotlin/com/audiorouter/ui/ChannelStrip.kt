package com.audiorouter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream
import com.audiorouter.ui.theme.*

@Composable
fun ChannelStrip(
    channel: AudioChannel,
    volume: Int,
    muted: Boolean,
    streams: List<AudioStream>,
    availableOutputs: List<Pair<Int, String>>,
    selectedOutput: String,
    onVolumeChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    onStreamRemoved: (AudioStream) -> Unit,
    onOutputChanged: (String) -> Unit,
    isDropTarget: Boolean = false,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isDropTarget) Orange500 else DividerDark
    // Local slider state prevents external recompositions (e.g. stream list changes)
    // from snapping the slider back mid-drag
    var sliderVolume by remember { mutableStateOf(volume.toFloat()) }
    LaunchedEffect(volume) { sliderVolume = volume.toFloat() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(140.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        // Channel name
        Text(
            text = channel.displayName.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (channel == AudioChannel.MASTER) Orange500
                    else MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(8.dp))

        // Volume percent label
        Text(
            text = "${sliderVolume.toInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(4.dp))

        // Vertical slider — rotated 270° to render as vertical
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 56.dp)
        ) {
            Slider(
                value = sliderVolume,
                onValueChange = {
                    sliderVolume = it
                    onVolumeChange(it.toInt())
                },
                valueRange = 0f..100f,
                enabled = !muted,
                colors = SliderDefaults.colors(
                    thumbColor = Orange500,
                    activeTrackColor = Orange500,
                    inactiveTrackColor = DividerDark
                ),
                modifier = Modifier
                    .graphicsLayer { rotationZ = 270f }
                    .width(120.dp)
            )
        }

        // Mute button
        IconButton(onClick = onMuteToggle) {
            Icon(
                imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (muted) "Unmute" else "Mute",
                tint = if (muted) MaterialTheme.colorScheme.error else Orange500
            )
        }

        // Assigned streams
        if (streams.isNotEmpty()) {
            HorizontalDivider(color = DividerDark, modifier = Modifier.padding(vertical = 4.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                items(streams, key = { it.sinkInputId }) { stream ->
                    StreamCard(
                        stream = stream,
                        onRemove = { onStreamRemoved(stream) }
                    )
                }
            }
        }

        // Per-channel output selector (not shown for MASTER)
        if (channel != AudioChannel.MASTER && availableOutputs.isNotEmpty()) {
            HorizontalDivider(color = DividerDark, modifier = Modifier.padding(vertical = 4.dp))
            ChannelOutputSelector(
                availableOutputs = availableOutputs,
                selectedOutput = selectedOutput,
                onOutputChanged = onOutputChanged
            )
        }
    }
}

@Composable
private fun ChannelOutputSelector(
    availableOutputs: List<Pair<Int, String>>,
    selectedOutput: String,
    onOutputChanged: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = availableOutputs
        .firstOrNull { it.second == selectedOutput }?.second
        ?.substringAfterLast('.')
        ?.ifBlank { selectedOutput }
        ?: selectedOutput.substringAfterLast('.').ifBlank { "Default" }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            availableOutputs.forEach { (_, name) ->
                val shortName = name.substringAfterLast('.').ifBlank { name }
                DropdownMenuItem(
                    text = {
                        Text(shortName, style = MaterialTheme.typography.bodySmall)
                    },
                    onClick = {
                        onOutputChanged(name)
                        expanded = false
                    }
                )
            }
        }
    }
}
