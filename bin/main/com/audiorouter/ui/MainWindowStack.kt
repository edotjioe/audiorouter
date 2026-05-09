package com.audiorouter.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream
import com.audiorouter.model.RoutingConfig
import com.audiorouter.service.LevelMonitor
import com.audiorouter.ui.stack.DragController
import com.audiorouter.ui.stack.VuMeterStereo
import com.audiorouter.ui.theme.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// ── Shared UI state ───────────────────────────────────────────────────────

data class MainWindowState(
    val config: RoutingConfig,
    val streams: List<AudioStream>,
    val availableSinks: List<Pair<Int, String>>,
    val availableSources: List<Pair<Int, String>>,
    val volumes: Map<AudioChannel, Int>,
    val mutes: Map<AudioChannel, Boolean>,
    val channelOutputs: Map<AudioChannel, String>,
    val channelEq: Map<AudioChannel, com.audiorouter.model.EqSettings> = emptyMap()
)

// ── Density ───────────────────────────────────────────────────────────────
enum class StackDensity(
    val rowHeight: Dp,
    val rowGap: Dp,
    val outerPadding: Dp,
    val labelGap: Dp
) {
    Compact(56.dp, 6.dp, 14.dp, 6.dp),
    Comfortable(72.dp, 9.dp, 18.dp, 8.dp),
    Spacious(88.dp, 14.dp, 22.dp, 10.dp);
}

// ── Column width percentages ──────────────────────────────────────────────
// Fixed columns are sized as a % of DESIGN_WIDTH; STREAMS gets all remaining space.
// Below DESIGN_WIDTH the whole UI scales down uniformly; above it only STREAMS grows.
private val DESIGN_WIDTH = 1100.dp
private fun colDp(pct: Float) = DESIGN_WIDTH * (pct / 100f)

private object Cols {
    const val CHANNEL = 8f   // % of DESIGN_WIDTH — fixed
    const val LEVEL   = 17f  // % of DESIGN_WIDTH — fixed
    const val VOLUME  = 15f  // % of DESIGN_WIDTH — fixed
    const val OUTPUT  = 10f  // % of DESIGN_WIDTH — fixed
    const val ACTIONS = 10f  // % of DESIGN_WIDTH — fixed
    // STREAMS: weight(1f) — absorbs all remaining space
}

// Channel hue lookup
private fun AudioChannel.hue(): Color = when (this) {
    AudioChannel.MASTER -> HueMaster
    AudioChannel.GAME   -> HueGame
    AudioChannel.CHAT   -> HueChat
    AudioChannel.MEDIA  -> HueMedia
    AudioChannel.AUX    -> HueAux
    AudioChannel.MIC    -> HueMic
}

// ── Main entry ────────────────────────────────────────────────────────────
@Composable
fun MainWindowStack(
    state: MainWindowState,
    levelMonitor: LevelMonitor,
    onVolumeChange: (AudioChannel, Int) -> Unit,
    onMuteToggle: (AudioChannel) -> Unit,
    onStreamAssigned: (AudioStream, AudioChannel) -> Unit,
    onStreamUnassigned: (AudioStream) -> Unit,
    onOutputSinkChanged: (String) -> Unit,
    onChannelOutputChanged: (AudioChannel, String) -> Unit,
    onEqChanged: (AudioChannel, com.audiorouter.model.EqSettings) -> Unit = { _, _ -> },
    initialDensity: StackDensity = StackDensity.Comfortable
) {
    var density by remember { mutableStateOf(initialDensity) }
    var expandedEqChannel by remember { mutableStateOf<AudioChannel?>(null) }
    val drag = remember { DragController() }

    val assignedStreams = state.streams.filter { it.assignedChannel != null }
    val unassignedStreams = state.streams.filter { it.assignedChannel == null }
    val channels = listOf(AudioChannel.MASTER) + AudioChannel.routingChannels

    // Capture original density before we override it for scaling
    val origDensity = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(StackBackdrop)
    ) {
        // Only scale DOWN when narrower than design width; at or above design width
        // the layout fills naturally and extra space goes to the STREAMS column.
        val scale = (maxWidth / DESIGN_WIDTH).coerceIn(0.4f, 1.0f)

        // Overriding LocalDensity uniformly scales every dp and sp value,
        // keeping layout coordinates, touch targets, and boundsInRoot in sync.
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = origDensity.density * scale,
                fontScale = origDensity.fontScale * scale
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(density.outerPadding)
            ) {
                // ── Top bar ───────────────────────────────────────────────
                TopBar(
                    availableSinks = state.availableSinks,
                    selectedSinkName = state.config.outputSinkName,
                    onSinkSelected = onOutputSinkChanged,
                    density = density,
                    onDensityChange = { density = it },
                    streamCount = state.streams.size,
                    channelCount = channels.size
                )

                Spacer(Modifier.height(density.outerPadding))

                // ── Body: channel list + unassigned drawer ────────────────
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(density.outerPadding)
                ) {
                    // Scrollable channel rows — Box clips, Column scrolls
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        val channelScroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(channelScroll),
                            verticalArrangement = Arrangement.spacedBy(density.rowGap)
                        ) {
                            ColumnHeaders()
                            channels.forEach { channel ->
                                val streams = assignedStreams.filter { it.assignedChannel == channel }
                                val levels by levelMonitor.levelFlow(channel).collectAsState()
                                ChannelRow(
                                    channel = channel,
                                    volume = state.volumes[channel] ?: 100,
                                    muted = state.mutes[channel] ?: false,
                                    streams = streams,
                                    availableOutputs = if (channel == AudioChannel.MIC) state.availableSources else state.availableSinks,
                                    selectedOutput = state.channelOutputs[channel] ?: "",
                                    density = density,
                                    levelL = if (state.mutes[channel] == true) 0f else levels.first,
                                    levelR = if (state.mutes[channel] == true) 0f else levels.second,
                                    isDropTarget = drag.draggingStream != null && drag.hoveredChannel == channel,
                                    eqSettings = if (channel != AudioChannel.MASTER) state.channelEq[channel] else null,
                                    eqExpanded = expandedEqChannel == channel,
                                    onEqToggleExpand = {
                                        expandedEqChannel = if (expandedEqChannel == channel) null else channel
                                    },
                                    onEqChanged = { settings -> onEqChanged(channel, settings) },
                                    onVolumeChange = { onVolumeChange(channel, it) },
                                    onMuteToggle = { onMuteToggle(channel) },
                                    onStreamRemoved = { onStreamUnassigned(it) },
                                    onOutputChanged = { sink -> onChannelOutputChanged(channel, sink) },
                                    drag = drag
                                )
                            }
                        }
                        // Scrollbar — visible only when content overflows
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(channelScroll),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            style = LocalScrollbarStyle.current.copy(
                                unhoverColor = Color(0x33FFFFFF),
                                hoverColor   = Color(0x66FFFFFF)
                            )
                        )
                    }

                    // Unassigned drawer
                    UnassignedDrawer(
                        streams = unassignedStreams,
                        totalStreams = state.streams.size,
                        drag = drag,
                        density = density,
                        onAssign = { stream, channel -> onStreamAssigned(stream, channel) }
                    )
                }
            }

            // ── Drag overlay (floats above everything, in the scaled coordinate space) ─
            DragFloater(drag = drag)
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────
@Composable
private fun TopBar(
    availableSinks: List<Pair<Int, String>>,
    selectedSinkName: String,
    onSinkSelected: (String) -> Unit,
    density: StackDensity,
    onDensityChange: (StackDensity) -> Unit,
    streamCount: Int,
    channelCount: Int
) {
    val cpu = rememberCpuUsage()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        // Logo glyph
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(listOf(Cyan500, Color(0xFF6E7BE5))))
                .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(9.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("AudioRouter", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
            Text(
                "LIST VIEW · $channelCount CHANNELS",
                color = TextDim, fontSize = 10.sp, letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.weight(1f))

        // System stats
        StatPill(label = "Sample rate", value = "48 kHz · 24-bit")
        Spacer(Modifier.width(16.dp))
        StatPill(label = "CPU", value = "${"%.1f".format(cpu)}%")
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextFaint, fontSize = 10.sp, letterSpacing = 1.0.sp)
        Spacer(Modifier.width(6.dp))
        Text(value, color = TextMid, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun rememberCpuUsage(): Float {
    var cpu by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var prevIdle = 0L; var prevTotal = 0L
        while (true) {
            try {
                val parts = java.io.File("/proc/stat").readLines().first()
                    .split("\\s+".toRegex()).drop(1).map { it.toLong() }
                val idle = parts[3] + parts[4]
                val total = parts.sum()
                if (prevTotal > 0) {
                    val dIdle = idle - prevIdle; val dTotal = total - prevTotal
                    cpu = if (dTotal > 0) (1f - dIdle.toFloat() / dTotal) * 100f else cpu
                }
                prevIdle = idle; prevTotal = total
            } catch (_: Exception) {}
            delay(2000)
        }
    }
    return cpu
}

// ── Column headers ────────────────────────────────────────────────────────
@Composable
private fun ColumnHeaders() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderCell("CHANNEL", Modifier.width(colDp(Cols.CHANNEL)))
        HeaderCell("STREAMS", Modifier.weight(1f))
        HeaderCell("LEVEL",   Modifier.width(colDp(Cols.LEVEL)))
        HeaderCell("VOLUME",  Modifier.width(colDp(Cols.VOLUME)))
        HeaderCell("OUTPUT",  Modifier.width(colDp(Cols.OUTPUT)))
        Box(Modifier.width(colDp(Cols.ACTIONS))) // actions column — no label
    }
}

@Composable
private fun RowScope.HeaderCell(label: String, modifier: Modifier) {
    Box(modifier = modifier) {
        Text(label, color = TextFaint, fontSize = 9.5.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Channel row ───────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelRow(
    channel: AudioChannel,
    volume: Int,
    muted: Boolean,
    streams: List<AudioStream>,
    availableOutputs: List<Pair<Int, String>>,
    selectedOutput: String,
    density: StackDensity,
    levelL: Float,
    levelR: Float,
    isDropTarget: Boolean,
    eqSettings: com.audiorouter.model.EqSettings?,
    eqExpanded: Boolean,
    onEqToggleExpand: () -> Unit,
    onEqChanged: (com.audiorouter.model.EqSettings) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    onStreamRemoved: (AudioStream) -> Unit,
    onOutputChanged: (String) -> Unit,
    drag: DragController
) {
    Column {
    val hue = channel.hue()
    val borderColor = if (isDropTarget) Cyan500 else GlassStroke
    val borderWidth = if (isDropTarget) 1.5.dp else 1.dp

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = density.rowHeight)
            .onGloballyPositioned { coords ->
                val r = coords.boundsInRoot()
                drag.registerTarget(channel, Rect(r.left, r.top, r.right, r.bottom))
            }
            .clip(RoundedCornerShape(14.dp))
            .background(GlassFill)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .drawBehind {
                drawRect(
                    color = if (muted) hue.copy(alpha = 0.25f) else hue,
                    topLeft = Offset(0f, 0f),
                    size = Size(3f, size.height)
                )
                if (isDropTarget) {
                    drawRect(
                        color = Cyan500.copy(alpha = 0.10f),
                        topLeft = Offset.Zero,
                        size = size
                    )
                }
            }
            .padding(horizontal = 14.dp)
    ) {
        // Channel name
        Column(modifier = Modifier.width(colDp(Cols.CHANNEL))) {
            Text(
                channel.displayName.uppercase(),
                color = hue,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp
            )
            Text(
                if (channel == AudioChannel.MASTER) "sum bus" else "aux bus",
                color = TextFaint,
                fontSize = 9.5.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Streams — takes all remaining space; wraps to additional lines when there are many
        FlowRow(
            modifier = Modifier
                .weight(1f)
                .padding(top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (streams.isEmpty()) {
                EmptyDropHint(isDropTarget = isDropTarget)
            } else {
                streams.forEach { stream ->
                    StreamChip(
                        stream = stream,
                        hue = hue,
                        onRemove = { onStreamRemoved(stream) }
                    )
                }
            }
        }

        // VU meters
        VuMeterStereo(
            levelL = levelL,
            levelR = levelR,
            modifier = Modifier.width(colDp(Cols.LEVEL)).fillMaxWidth()
        )

        // Volume slider + percent
        Row(
            modifier = Modifier.width(colDp(Cols.VOLUME)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VolumeSlider(
                value = volume,
                muted = muted,
                hue = hue,
                onChange = onVolumeChange,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                if (muted) "—" else volume.toString(),
                color = if (muted) TextFaint else TextHi,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(28.dp)
            )
        }

        // Output picker
        Box(modifier = Modifier.width(colDp(Cols.OUTPUT))) {
            if (channel != AudioChannel.MASTER) {
                ChannelOutputPicker(
                    availableOutputs = availableOutputs,
                    selectedOutput = selectedOutput,
                    onOutputChanged = onOutputChanged
                )
            }
        }

        // Mute + EQ toggle
        Row(
            modifier = Modifier.width(colDp(Cols.ACTIONS)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MuteButton(muted = muted, onClick = onMuteToggle)
            if (eqSettings != null) {
                val eqActive = eqSettings.enabled
                IconButton(onClick = onEqToggleExpand, modifier = Modifier.size(32.dp)) {
                    Text(
                        "EQ",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        color = when {
                            eqExpanded -> hue
                            eqActive   -> hue.copy(alpha = 0.7f)
                            else       -> TextFaint
                        }
                    )
                }
            }
        }
    }

    // EQ panel (expanded below the row)
    if (eqSettings != null && eqExpanded) {
        com.audiorouter.ui.stack.EqPanel(
            settings = eqSettings,
            hue = hue,
            onSettingsChanged = onEqChanged
        )
    }

    // Cleanup target registration when leaving composition
    DisposableEffect(channel) {
        onDispose { drag.unregisterTarget(channel) }
    }
    } // Column
}

@Composable
private fun EmptyDropHint(isDropTarget: Boolean) {
    val color = if (isDropTarget) Cyan300 else TextFaint
    Text(
        if (isDropTarget) "release to assign" else "drop streams here",
        color = color,
        fontSize = 10.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .border(1.dp, if (isDropTarget) Cyan500.copy(alpha = 0.5f) else GlassStroke, RoundedCornerShape(99.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun StreamChip(
    stream: AudioStream,
    hue: Color,
    onRemove: () -> Unit
) {
    val initial = stream.appName.firstOrNull()?.uppercase() ?: "?"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(Glass1)
            .border(1.dp, GlassStroke, RoundedCornerShape(99.dp))
            .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(hue),
            contentAlignment = Alignment.Center
        ) {
            Text(initial, color = Color(0xFF0A0E14), fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            stream.appName,
            color = TextHi,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 80.dp)
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .pointerInput(Unit) { detectTapGestures { onRemove() } },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Remove", tint = TextDim, modifier = Modifier.size(10.dp))
        }
    }
}

@Composable
private fun OverflowChip(
    overflow: List<AudioStream>,
    hue: Color,
    onRemove: (AudioStream) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            "+${overflow.size}",
            color = TextDim,
            fontSize = 10.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(Glass1)
                .border(1.dp, GlassStroke, RoundedCornerShape(99.dp))
                .pointerInput(Unit) { detectTapGestures { expanded = true } }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                "MORE STREAMS",
                color = TextFaint,
                fontSize = 9.sp,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            overflow.forEach { stream ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(18.dp).clip(CircleShape).background(hue),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stream.appName.firstOrNull()?.uppercase() ?: "?",
                                    color = Color(0xFF0A0E14),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(stream.appName, color = TextHi, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = TextDim,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    },
                    onClick = {
                        onRemove(stream)
                        if (overflow.size == 1) expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeSlider(
    value: Int,
    muted: Boolean,
    hue: Color,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var local by remember { mutableStateOf(value.toFloat()) }
    LaunchedEffect(value) { local = value.toFloat() }

    Slider(
        value = local,
        onValueChange = { local = it; onChange(it.toInt()) },
        valueRange = 0f..100f,
        enabled = !muted,
        colors = SliderDefaults.colors(
            thumbColor = TextHi,
            activeTrackColor = if (muted) TextFaint else hue,
            inactiveTrackColor = Glass3
        ),
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(1.dp),
                colors = SliderDefaults.colors(
                    activeTrackColor = if (muted) TextFaint else hue,
                    inactiveTrackColor = Glass3
                )
            )
        },
        modifier = modifier
    )
}

@Composable
private fun ChannelOutputPicker(
    availableOutputs: List<Pair<Int, String>>,
    selectedOutput: String,
    onOutputChanged: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = selectedOutput.substringAfterLast('.').ifBlank { selectedOutput.ifBlank { "—" } }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0D1117))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                .pointerInput(Unit) { detectTapGestures { expanded = true } }
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                label,
                color = TextHi,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = TextDim, modifier = Modifier.size(13.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            availableOutputs.forEach { (_, name) ->
                DropdownMenuItem(
                    text = { Text(name.substringAfterLast('.').ifBlank { name }, fontSize = 12.sp) },
                    onClick = { onOutputChanged(name); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun MuteButton(muted: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (muted) MuteRed.copy(alpha = 0.15f) else Glass1)
            .border(
                1.dp,
                if (muted) MuteRed.copy(alpha = 0.4f) else GlassStroke,
                RoundedCornerShape(8.dp)
            )
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (muted) "Unmute" else "Mute",
            tint = if (muted) MuteRed else TextHi,
            modifier = Modifier.size(14.dp)
        )
    }
}

// ── Unassigned drawer ─────────────────────────────────────────────────────
@Composable
private fun UnassignedDrawer(
    streams: List<AudioStream>,
    totalStreams: Int,
    drag: DragController,
    density: StackDensity,
    onAssign: (AudioStream, AudioChannel) -> Unit
) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF111820))
            .border(1.dp, GlassStroke, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        ) {
            Text("UNASSIGNED", color = TextMid, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Spacer(Modifier.weight(1f))
            if (streams.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Cyan500.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(streams.size.toString(), color = Cyan400, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            when {
                totalStreams == 0 -> "No streams detected.\nPlay audio in any app."
                streams.isEmpty() -> "All streams assigned."
                else -> "Drag a stream onto a\nchannel row to route it."
            },
            color = TextFaint,
            fontSize = 9.5.sp,
            lineHeight = 13.sp,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(streams, key = { it.sinkInputId }) { stream ->
                DraggableUnassignedItem(stream = stream, drag = drag, onAssign = onAssign)
            }
        }
    }
}

@Composable
private fun DraggableUnassignedItem(
    stream: AudioStream,
    drag: DragController,
    onAssign: (AudioStream, AudioChannel) -> Unit
) {
    val density = LocalDensity.current
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    var menuExpanded by remember { mutableStateOf(false) }
    val isThisDragging = drag.draggingStream == stream

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowBounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(6.dp))
            .background(if (isThisDragging) Glass3 else Glass1)
            .pointerInput(stream.sinkInputId) {
                detectDragGestures(
                    onDragStart = { offsetInCard ->
                        drag.begin(
                            stream = stream,
                            pointerInRoot = Offset(rowBounds.left + offsetInCard.x, rowBounds.top + offsetInCard.y),
                            cardSize = Offset(rowBounds.width, rowBounds.height),
                            grab = offsetInCard
                        )
                    },
                    onDrag = { change, dragAmount -> change.consume(); drag.update(drag.pointer + dragAmount) },
                    onDragEnd = {
                        val target = drag.end()
                        if (target != null && target != AudioChannel.MASTER) onAssign(stream, target)
                    },
                    onDragCancel = { drag.cancel() }
                )
            }
            .pointerInput(stream.sinkInputId) { detectTapGestures { menuExpanded = true } }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            // Colored left accent stripe
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Cyan500.copy(alpha = if (isThisDragging) 1f else 0.5f))
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    stream.appName,
                    color = if (isThisDragging) TextMid else TextHi,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (stream.appBinary.isNotBlank() && stream.appBinary != stream.appName) {
                    Text(stream.appBinary, color = TextFaint, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            Text(
                "ASSIGN TO CHANNEL",
                color = TextFaint,
                fontSize = 9.sp,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            AudioChannel.routingChannels.forEach { ch ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ch.hue()))
                            Text(ch.displayName, fontSize = 12.sp)
                        }
                    },
                    onClick = { onAssign(stream, ch); menuExpanded = false }
                )
            }
        }
    }
}

// ── Drag floater ──────────────────────────────────────────────────────────
@Composable
private fun DragFloater(drag: DragController) {
    val s = drag.draggingStream ?: return
    val density = LocalDensity.current
    val tl = drag.floaterTopLeft()
    val widthPx = drag.sourceSize.x.takeIf { it > 0 } ?: 200f
    val widthDp = with(density) { widthPx.toDp() }

    Box(
        modifier = Modifier
            .offset { IntOffset(tl.x.roundToInt(), tl.y.roundToInt()) }
            .width(widthDp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Cyan500.copy(alpha = 0.85f), Color(0xFF3B5BD9).copy(alpha = 0.85f))
                )
            )
            .border(1.dp, Cyan500, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.DragIndicator,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                s.appName,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
