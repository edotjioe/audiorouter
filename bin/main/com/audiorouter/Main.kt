package com.audiorouter

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream
import com.audiorouter.persistence.ConfigRepository
import com.audiorouter.service.*
import com.audiorouter.ui.MainWindowStack
import com.audiorouter.ui.MainWindowState
import com.audiorouter.ui.theme.StackTheme
import dorkbox.systemTray.MenuItem as TrayItem
import dorkbox.systemTray.Separator as TraySeparator
import dorkbox.systemTray.SystemTray
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.swing.SwingUtilities

private val log = KotlinLogging.logger {}

fun main() = application {
    val scope = rememberCoroutineScope()
    val appState = remember { AppState(scope) }

    LaunchedEffect(Unit) {
        appState.initialize()
    }

    // Register shutdown hook for SIGTERM / desktop logout
    DisposableEffect(Unit) {
        val hook = Thread { runBlocking { appState.shutdown() } }
        Runtime.getRuntime().addShutdownHook(hook)
        onDispose { runBlocking { appState.shutdown() } }
    }

    val windowState = rememberWindowState(width = 1440.dp, height = 700.dp)
    var windowVisible by remember { mutableStateOf(true) }

    // Dorkbox SystemTray — uses StatusNotifierItem on KDE Plasma 6 / Wayland,
    // falling back to XEmbed/AppIndicator on older DEs. Replaces Compose's Tray
    // composable which only supports the legacy XEmbed protocol.
    DisposableEffect(Unit) {
        SystemTray.DEBUG = false
        val tray = SystemTray.get()
        if (tray != null) {
            val iconStream = Thread.currentThread().contextClassLoader
                .getResourceAsStream("icon_tray.png")
            if (iconStream != null) tray.setImage(iconStream)
            tray.setTooltip("AudioRouter")

            fun showWindow() = SwingUtilities.invokeLater {
                windowVisible = true
                windowState.isMinimized = false
            }

            tray.menu.add(TrayItem("Show AudioRouter") { showWindow() })
            tray.menu.add(TrayItem("Close Window") {
                SwingUtilities.invokeLater { windowVisible = false }
            })
            tray.menu.add(TraySeparator())
            tray.menu.add(TrayItem("Quit AudioRouter") {
                scope.launch {
                    appState.shutdown()
                    exitApplication()
                }
            })
        } else {
            log.warn { "System tray not available on this desktop environment" }
        }
        onDispose { SystemTray.get()?.shutdown() }
    }

    Window(
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = "AudioRouter",
        state = windowState
    ) {
        // 'window' is FrameWindowScope.window (ComposeWindow / JFrame)
        LaunchedEffect(windowVisible) {
            if (windowVisible) {
                window.toFront()
                window.requestFocus()
            }
        }

        StackTheme {
                val uiState by appState.uiState.collectAsState()
                MainWindowStack(
                    state = uiState,
                    levelMonitor = appState.levelMonitor,
                    onVolumeChange = { channel, vol -> appState.volumeController.setVolume(channel, vol) },
                    onMuteToggle = { channel -> appState.volumeController.toggleMute(channel) },
                    onStreamAssigned = { stream, channel ->
                        scope.launch { appState.routingEngine.assignStream(stream, channel) }
                    },
                    onStreamUnassigned = { stream ->
                        scope.launch { appState.routingEngine.unassignStream(stream) }
                    },
                    onOutputSinkChanged = { sinkName ->
                        scope.launch { appState.changeDefaultOutput(sinkName) }
                    },
                    onChannelOutputChanged = { channel, sinkName ->
                        scope.launch { appState.changeChannelOutput(channel, sinkName) }
                    }
                )
            }
        }
}

class AppState(private val scope: CoroutineScope) {

    private val audioService = AudioServiceFactory.create()
    private val configRepo = ConfigRepository(scope)
    val volumeController = VolumeController(audioService, configRepo, scope)
    private val sinkManager = VirtualSinkManager(audioService)
    private val streamMonitor = StreamMonitor(audioService, scope)
    val routingEngine = RoutingEngine(streamMonitor, audioService, configRepo, scope)
    val levelMonitor = LevelMonitor(audioService, scope)

    private val _availableSinks = MutableStateFlow<List<Pair<Int, String>>>(emptyList())

    val uiState: StateFlow<MainWindowState> = combine(
        configRepo.config,
        routingEngine.streams,
        _availableSinks
    ) { config, streams, sinks ->
        Triple(config, streams, sinks)
    }.combine(
        combine(AudioChannel.entries.map { volumeController.volumeFlow(it) }) { it.toList() }
    ) { (config, streams, sinks), volumes ->
        Pair(Triple(config, streams, sinks), volumes)
    }.combine(
        combine(AudioChannel.entries.map { volumeController.muteFlow(it) }) { it.toList() }
    ) { (triple, volumes), mutes ->
        val (config, streams, sinks) = triple
        val channels = AudioChannel.entries
        MainWindowState(
            config = config,
            streams = streams,
            availableSinks = sinks,
            volumes = channels.associateWith { ch -> volumes[channels.indexOf(ch)] },
            mutes = channels.associateWith { ch -> mutes[channels.indexOf(ch)] },
            channelOutputs = AudioChannel.routingChannels.associateWith { ch -> config.outputSinkFor(ch) }
        )
    }.stateIn(scope, SharingStarted.Eagerly, defaultUiState())

    suspend fun initialize() {
        log.info { "AudioRouter starting up" }
        configRepo.load()

        _availableSinks.value = audioService.listRealSinks()

        val outputSink = configRepo.config.value.outputSinkName.ifBlank {
            _availableSinks.value.firstOrNull()?.second ?: ""
        }
        if (outputSink.isNotBlank()) {
            configRepo.update { it.copy(outputSinkName = outputSink) }
        }

        sinkManager.initialize(configRepo.config.value)
        volumeController.init()
        volumeController.applyStoredVolumes()

        streamMonitor.start()
        routingEngine.start()
        levelMonitor.start()

        log.info { "AudioRouter initialized with output: $outputSink" }
    }

    suspend fun changeDefaultOutput(sinkName: String) {
        val config = configRepo.config.value
        configRepo.update { it.copy(outputSinkName = sinkName) }
        sinkManager.updateDefaultOutput(sinkName, config)
        log.info { "Default output changed to: $sinkName" }
    }

    suspend fun changeChannelOutput(channel: AudioChannel, sinkName: String) {
        configRepo.update { it.withChannelOutput(channel, sinkName) }
        sinkManager.updateChannelOutput(channel, sinkName)
        log.info { "Channel ${channel.displayName} output changed to: $sinkName" }
    }

    suspend fun shutdown() {
        log.info { "AudioRouter shutting down" }
        routingEngine.stop()
        streamMonitor.stop()
        configRepo.flushImmediately()
        sinkManager.cleanup()
        log.info { "AudioRouter shutdown complete" }
    }

    private fun defaultUiState() = MainWindowState(
        config = com.audiorouter.model.RoutingConfig(),
        streams = emptyList(),
        availableSinks = emptyList(),
        volumes = AudioChannel.entries.associateWith { 100 },
        mutes = AudioChannel.entries.associateWith { false },
        channelOutputs = AudioChannel.routingChannels.associateWith { "" }
    )
}
