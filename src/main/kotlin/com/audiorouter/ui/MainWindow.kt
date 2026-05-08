package com.audiorouter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream
import com.audiorouter.model.RoutingConfig
import com.audiorouter.ui.theme.*

data class MainWindowState(
    val config: RoutingConfig,
    val streams: List<AudioStream>,
    val availableSinks: List<Pair<Int, String>>,
    val volumes: Map<AudioChannel, Int>,
    val mutes: Map<AudioChannel, Boolean>,
    val channelOutputs: Map<AudioChannel, String>
)

@Composable
fun MainWindow(
    state: MainWindowState,
    onVolumeChange: (AudioChannel, Int) -> Unit,
    onMuteToggle: (AudioChannel) -> Unit,
    onStreamAssigned: (AudioStream, AudioChannel) -> Unit,
    onStreamUnassigned: (AudioStream) -> Unit,
    onOutputSinkChanged: (String) -> Unit,
    onChannelOutputChanged: (AudioChannel, String) -> Unit
) {
    val assignedStreams = state.streams.filter { it.assignedChannel != null }
    val unassignedStreams = state.streams.filter { it.assignedChannel == null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp)
    ) {
        // Header bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(
                "AudioRouter",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Orange500
            )
            Spacer(Modifier.weight(1f))
            OutputSelector(
                availableSinks = state.availableSinks,
                selectedSinkName = state.config.outputSinkName,
                onSinkSelected = onOutputSinkChanged
            )
        }

        HorizontalDivider(color = DividerDark, modifier = Modifier.padding(bottom = 16.dp))

        // Channel strips + unassigned panel
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Channel strips (MASTER + routing channels)
            val channels = listOf(AudioChannel.MASTER) + AudioChannel.routingChannels
            channels.forEach { channel ->
                val channelStreams = assignedStreams.filter { it.assignedChannel == channel }
                ChannelStrip(
                    channel = channel,
                    volume = state.volumes[channel] ?: 100,
                    muted = state.mutes[channel] ?: false,
                    streams = channelStreams,
                    availableOutputs = state.availableSinks,
                    selectedOutput = state.channelOutputs[channel] ?: "",
                    onVolumeChange = { onVolumeChange(channel, it) },
                    onMuteToggle = { onMuteToggle(channel) },
                    onStreamRemoved = { onStreamUnassigned(it) },
                    onOutputChanged = { sink -> onChannelOutputChanged(channel, sink) },
                    modifier = Modifier.fillMaxHeight()
                )
            }

            // Unassigned / available apps panel
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(SurfaceDark, RoundedCornerShape(8.dp))
                    .border(1.dp, DividerDark, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    "Unassigned",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (unassignedStreams.isEmpty() && state.streams.isEmpty()) {
                    Text(
                        "No audio streams detected.\nPlay audio in any app to see it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (unassignedStreams.isEmpty()) {
                    Text(
                        "All streams assigned.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(unassignedStreams, key = { it.sinkInputId }) { stream ->
                            UnassignedStreamItem(
                                stream = stream,
                                onAssign = { channel -> onStreamAssigned(stream, channel) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnassignedStreamItem(
    stream: AudioStream,
    onAssign: (AudioChannel) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        StreamCard(
            stream = stream,
            onRemove = null,
            modifier = Modifier
        )
        // Clickable overlay to open channel assignment menu
        Box(modifier = Modifier.matchParentSize().clickable { menuExpanded = true })
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            Text(
                "Assign to channel:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            AudioChannel.routingChannels.forEach { channel ->
                DropdownMenuItem(
                    text = { Text(channel.displayName) },
                    onClick = {
                        onAssign(channel)
                        menuExpanded = false
                    }
                )
            }
        }
    }
}
