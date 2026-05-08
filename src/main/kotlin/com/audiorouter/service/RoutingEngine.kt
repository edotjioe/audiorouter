package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream
import com.audiorouter.persistence.ConfigRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val log = KotlinLogging.logger {}

class RoutingEngine(
    private val monitor: StreamMonitor,
    private val pipeWire: PipeWireService,
    private val configRepo: ConfigRepository,
    private val scope: CoroutineScope
) {
    private val _streams = MutableStateFlow<List<AudioStream>>(emptyList())
    val streams: StateFlow<List<AudioStream>> = _streams.asStateFlow()

    private var engineJob: Job? = null

    fun start() {
        engineJob = scope.launch {
            monitor.events.collect { event ->
                when (event) {
                    is AudioEvent.SinkInputAdded -> handleNewStream(event.sinkInputId)
                    is AudioEvent.SinkInputRemoved -> handleRemovedStream(event.sinkInputId)
                    is AudioEvent.SinkChanged -> refreshAllStreams()
                    is AudioEvent.AllStreamsRefresh -> refreshAllStreams()
                }
            }
        }
        scope.launch {
            refreshAllStreams()
            applyRulesToExistingStreams()
        }
    }

    fun stop() {
        engineJob?.cancel()
    }

    suspend fun assignStream(stream: AudioStream, channel: AudioChannel) {
        val sinkName = channel.sinkName
        val moved = pipeWire.moveSinkInput(stream.sinkInputId, sinkName)
        if (moved) {
            configRepo.update { it.withRule(stream.appName, channel) }
            refreshAllStreams()
            log.info { "Assigned '${stream.appName}' → ${channel.displayName}" }
        } else {
            log.warn { "Failed to move sink-input ${stream.sinkInputId} to $sinkName" }
        }
    }

    suspend fun unassignStream(stream: AudioStream) {
        // Move the physical stream off the virtual channel sink back to the real output,
        // otherwise the next refresh will still see it on AudioRouter_X and re-show it as assigned
        val outputSink = configRepo.config.value.outputSinkName
        if (outputSink.isNotBlank()) {
            pipeWire.moveSinkInput(stream.sinkInputId, outputSink)
        }
        configRepo.update { it.withoutRule(stream.appName) }
        refreshAllStreams()
    }

    private suspend fun handleNewStream(sinkInputId: Int) {
        // Always refresh so the UI sees the new stream, even if properties
        // aren't fully populated yet. Then apply routing if a rule matches.
        delay(150)
        refreshAllStreams()

        val stream = _streams.value.firstOrNull { it.sinkInputId == sinkInputId }
        if (stream != null) {
            val config = configRepo.config.value
            val channel = config.channelFor(stream.appName) ?: config.channelFor(stream.appBinary)
            if (channel != null) {
                val moved = pipeWire.moveSinkInput(sinkInputId, channel.sinkName)
                if (moved) {
                    log.info { "Auto-routed '${stream.appName}' → ${channel.displayName}" }
                    refreshAllStreams()
                }
            }
        } else {
            // Stream metadata may still be populating — retry once after a longer wait
            delay(500)
            refreshAllStreams()
        }
    }

    private suspend fun applyRulesToExistingStreams() {
        val config = configRepo.config.value
        var anyMoved = false
        for (stream in _streams.value) {
            val channel = config.channelFor(stream.appName) ?: config.channelFor(stream.appBinary)
            if (channel != null && stream.assignedChannel != channel) {
                val moved = pipeWire.moveSinkInput(stream.sinkInputId, channel.sinkName)
                if (moved) {
                    log.info { "Restored rule on startup: '${stream.appName}' → ${channel.displayName}" }
                    anyMoved = true
                }
            }
        }
        if (anyMoved) refreshAllStreams()
    }

    private suspend fun handleRemovedStream(sinkInputId: Int) {
        _streams.update { current -> current.filter { it.sinkInputId != sinkInputId } }
    }

    private suspend fun refreshAllStreams() {
        val rawStreams = pipeWire.listSinkInputs()
        val config = configRepo.config.value
        val sinks = pipeWire.listShortSinks()

        val enriched = rawStreams.map { stream ->
            val channel = sinks.firstOrNull { (id, _) -> id == stream.currentSinkId }
                ?.second
                ?.let { sinkName -> AudioChannel.routingChannels.firstOrNull { it.sinkName == sinkName } }
            stream.copy(assignedChannel = channel)
        }
        _streams.value = enriched
    }
}
