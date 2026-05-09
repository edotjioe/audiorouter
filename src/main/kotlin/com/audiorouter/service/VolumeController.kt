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

    // Channels whose audio volume is scaled by MASTER (all routing channels except MIC).
    private val masterScaled = AudioChannel.routingChannels.filter { it != AudioChannel.MIC }

    private fun effectiveVolume(channel: AudioChannel): Int {
        val vol = volumeFlows[channel]?.value ?: 100
        if (channel == AudioChannel.MASTER || channel == AudioChannel.MIC) return vol
        val master = volumeFlows[AudioChannel.MASTER]?.value ?: 100
        return (master * vol / 100.0).toInt().coerceIn(0, 100)
    }

    fun init() {
        val config = configRepo.config.value
        for (channel in AudioChannel.entries) {
            volumeFlows[channel]?.value = config.volumeFor(channel)
            muteFlows[channel]?.value = config.mutedFor(channel)
        }

        // When MASTER changes, recompute and push effective volumes for all scaled channels.
        scope.launch {
            volumeFlows[AudioChannel.MASTER]!!
                .debounce(100)
                .collect { masterPct ->
                    pipeWire.setSinkVolume(AudioChannel.MASTER.sinkName, masterPct)
                    configRepo.update { it.withVolume(AudioChannel.MASTER, masterPct) }
                    for (ch in masterScaled) {
                        val effective = effectiveVolume(ch)
                        pipeWire.setSinkVolume(ch.sinkName, effective)
                    }
                }
        }

        // Per-channel (non-MASTER) volume: apply effective volume (scaled by current master).
        for (channel in AudioChannel.entries.filter { it != AudioChannel.MASTER }) {
            scope.launch {
                volumeFlows[channel]!!
                    .debounce(100)
                    .collect { percent ->
                        val effective = effectiveVolume(channel)
                        pipeWire.setSinkVolume(channel.sinkName, effective)
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

        // MASTER mute
        scope.launch {
            muteFlows[AudioChannel.MASTER]!!
                .drop(1)
                .collect { muted ->
                    pipeWire.setSinkMute(AudioChannel.MASTER.sinkName, muted)
                    configRepo.update { it.withMute(AudioChannel.MASTER, muted) }
                }
        }
    }

    suspend fun applyStoredVolumes() {
        val config = configRepo.config.value
        for (channel in AudioChannel.entries) {
            val vol = config.volumeFor(channel)
            val muted = config.mutedFor(channel)
            volumeFlows[channel]?.value = vol
            muteFlows[channel]?.value = muted
        }
        // Apply effective volumes (master-scaled) to audio server
        for (channel in AudioChannel.entries) {
            pipeWire.setSinkVolume(channel.sinkName, effectiveVolume(channel))
            pipeWire.setSinkMute(channel.sinkName, config.mutedFor(channel))
            log.info { "Applied stored volume for ${channel.displayName}: ${effectiveVolume(channel)}% effective, muted=${config.mutedFor(channel)}" }
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
