package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.persistence.ConfigRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

private val log = KotlinLogging.logger {}

/**
 * Manages per-channel volume and mute state, debouncing rapid slider changes before
 * issuing audio-server calls.
 *
 * Each channel has a [MutableStateFlow] for volume and mute. [init] seeds them from the
 * persisted config and starts collection coroutines that:
 * - Debounce volume changes by 100 ms before calling [AudioService.setSinkVolume].
 * - Skip the initial mute value (already applied by [applyStoredVolumes] on startup) and only
 *   forward subsequent changes to [AudioService.setSinkMute].
 *
 * Both flows also persist their latest value to [ConfigRepository] on each emission.
 *
 * @param pipeWire   The platform audio backend.
 * @param configRepo Config repository used to seed initial state and persist changes.
 * @param scope      Coroutine scope that owns the collection jobs.
 */
@OptIn(FlowPreview::class)
class VolumeController(
    private val pipeWire: AudioService,
    private val configRepo: ConfigRepository,
    private val scope: CoroutineScope
) {
    private val volumeFlows = AudioChannel.entries.associate { channel ->
        channel to MutableStateFlow(100)
    }

    private val muteFlows = AudioChannel.entries.associate { channel ->
        channel to MutableStateFlow(false)
    }

    fun init() {
        val config = configRepo.config.value
        for (channel in AudioChannel.entries) {
            volumeFlows[channel]?.value = config.volumeFor(channel)
            muteFlows[channel]?.value = config.mutedFor(channel)
        }

        for (channel in AudioChannel.entries) {
            scope.launch {
                volumeFlows[channel]!!
                    .debounce(100)
                    .collect { percent ->
                        pipeWire.setSinkVolume(channel.sinkName, percent)
                        configRepo.update { it.withVolume(channel, percent) }
                    }
            }
            scope.launch {
                muteFlows[channel]!!
                    .drop(1) // skip initial value — already applied on startup
                    .collect { muted ->
                        pipeWire.setSinkMute(channel.sinkName, muted)
                        configRepo.update { it.withMute(channel, muted) }
                    }
            }
        }
    }

    suspend fun applyStoredVolumes() {
        val config = configRepo.config.value
        for (channel in AudioChannel.entries) {
            val vol = config.volumeFor(channel)
            val muted = config.mutedFor(channel)
            pipeWire.setSinkVolume(channel.sinkName, vol)
            pipeWire.setSinkMute(channel.sinkName, muted)
            log.info { "Applied stored volume for ${channel.displayName}: $vol%, muted=$muted" }
        }
    }

    fun setVolume(channel: AudioChannel, percent: Int) {
        volumeFlows[channel]?.value = percent.coerceIn(0, 100)
    }

    fun setMute(channel: AudioChannel, muted: Boolean) {
        muteFlows[channel]?.value = muted
    }

    fun toggleMute(channel: AudioChannel) {
        val flow = muteFlows[channel] ?: return
        flow.value = !flow.value
    }

    fun volumeFlow(channel: AudioChannel): StateFlow<Int> =
        volumeFlows[channel] ?: MutableStateFlow(100)

    fun muteFlow(channel: AudioChannel): StateFlow<Boolean> =
        muteFlows[channel] ?: MutableStateFlow(false)
}
