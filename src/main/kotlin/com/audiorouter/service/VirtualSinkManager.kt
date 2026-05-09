package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.EqSettings
import com.audiorouter.model.RoutingConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Manages the lifecycle of virtual audio sinks for each [AudioChannel].
 *
 * On Linux each channel gets a `module-null-sink` (the virtual input apps are routed to)
 * plus a `module-loopback` (which re-plays that sink's monitor stream on the real output device).
 * On macOS/Windows the underlying [AudioService] provides no-op stubs, so this class acts as
 * a bookkeeping layer only.
 *
 * Call [initialize] at startup and [cleanup] at shutdown. Any modules left over from a previous
 * crash are detected and removed by [initialize] before creating new ones.
 */
class VirtualSinkManager(private val pipeWire: AudioService) {

    /**
     * Module IDs per channel.
     *
     * Without EQ:  null-sink + loopback(monitor → real output)
     * With EQ:     null-sink + eqSink(ladspa, master=real output) + loopback(monitor → eqSink)
     *
     * [eqSink] = -1 means EQ is disabled. [eqGains] are stored so [updateChannelOutput] can
     * rebuild the LADSPA module with the correct gains when the output device changes.
     */
    private data class ModuleIds(
        val nullSink: Int,
        val loopback: Int,
        val eqSink: Int = -1,
        val eqGains: List<Float>? = null
    )

    private val modules = mutableMapOf<AudioChannel, ModuleIds>()

    /** Creates virtual sinks for all [AudioChannel.routingChannels], cleaning up orphans first. */
    suspend fun initialize(config: RoutingConfig) {
        cleanupOrphans()
        for (channel in AudioChannel.routingChannels) {
            val nullSinkId = pipeWire.loadNullSink(channel)
            if (nullSinkId < 0) {
                log.error { "Failed to create null sink for ${channel.displayName}" }
                continue
            }
            val outputSink = config.outputSinkFor(channel)
            val eq = config.eqFor(channel)
            val ids = loadSinkAndLoopback(channel, nullSinkId, outputSink, eq)
            modules[channel] = ids
            log.info { "Created virtual sink for ${channel.displayName}: eq=${ids.eqSink >= 0} → $outputSink" }
        }
    }

    /** Tears down and recreates the loopback (and EQ sink if active) for [channel]. */
    suspend fun updateChannelOutput(channel: AudioChannel, newOutputSinkName: String) {
        val ids = modules[channel] ?: return
        tearDownLoopbackAndEq(ids)
        val newIds = buildLoopback(channel, ids.nullSink, newOutputSinkName, ids.eqGains)
        modules[channel] = newIds
        log.info { "Channel ${channel.displayName} output → $newOutputSinkName" }
    }

    /** Applies new EQ settings for [channel], rebuilding the signal chain as needed. */
    suspend fun applyEq(channel: AudioChannel, settings: EqSettings, outputSinkName: String) {
        val ids = modules[channel] ?: return
        tearDownLoopbackAndEq(ids)
        val gains = if (settings.enabled) settings.gains else null
        val newIds = buildLoopback(channel, ids.nullSink, outputSinkName, gains)
        modules[channel] = newIds
        log.info { "EQ applied for ${channel.displayName}: enabled=${settings.enabled}" }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private suspend fun loadSinkAndLoopback(
        channel: AudioChannel, nullSinkId: Int, outputSink: String, eq: EqSettings
    ): ModuleIds {
        val gains = if (eq.enabled) eq.gains else null
        return buildLoopback(channel, nullSinkId, outputSink, gains)
    }

    /**
     * Builds the loopback (and optionally EQ LADSPA sink) for [channel].
     * If [gains] is non-null, loads an mbeq LADSPA sink and routes the loopback through it.
     */
    private suspend fun buildLoopback(
        channel: AudioChannel, nullSinkId: Int, outputSinkName: String, gains: List<Float>?
    ): ModuleIds {
        if (outputSinkName.isBlank()) return ModuleIds(nullSink = nullSinkId, loopback = -1)

        return if (gains != null) {
            val eqSinkId = pipeWire.loadEqSink(channel, gains, outputSinkName)
            if (eqSinkId >= 0) {
                // Route loopback monitor → EQ sink (EQ sink's master IS the real output)
                val loopbackId = pipeWire.loadLoopbackFromSource(
                    "${channel.sinkName}.monitor", "${channel.sinkName}_eq"
                )
                ModuleIds(nullSink = nullSinkId, loopback = loopbackId, eqSink = eqSinkId, eqGains = gains)
            } else {
                // mbeq unavailable — fall back to direct loopback
                val loopbackId = pipeWire.loadLoopback(channel, outputSinkName)
                ModuleIds(nullSink = nullSinkId, loopback = loopbackId)
            }
        } else {
            val loopbackId = pipeWire.loadLoopback(channel, outputSinkName)
            ModuleIds(nullSink = nullSinkId, loopback = loopbackId)
        }
    }

    /** Unloads the loopback and EQ sink modules, leaving the null-sink intact. */
    private suspend fun tearDownLoopbackAndEq(ids: ModuleIds) {
        if (ids.loopback >= 0) pipeWire.unloadModule(ids.loopback)
        if (ids.eqSink >= 0) pipeWire.unloadModule(ids.eqSink)
    }

    /**
     * Updates the loopback output for all channels that do not have a per-channel override set in [config].
     * Called when the user changes the global output device.
     */
    suspend fun updateDefaultOutput(newOutputSinkName: String, config: RoutingConfig) {
        // Only update channels that don't have a per-channel override
        for (channel in AudioChannel.routingChannels) {
            if (config.channelOutputSinks[channel.name].isNullOrBlank()) {
                updateChannelOutput(channel, newOutputSinkName)
            }
        }
    }

    /** Unloads all virtual sinks, loopbacks, and EQ chains created by this instance. */
    suspend fun cleanup() {
        for ((channel, ids) in modules.entries.toList()) {
            if (ids.loopback >= 0) pipeWire.unloadModule(ids.loopback)
            if (ids.eqSink >= 0) pipeWire.unloadModule(ids.eqSink)
            if (ids.nullSink >= 0) pipeWire.unloadModule(ids.nullSink)
            log.info { "Removed virtual sink for ${channel.displayName}" }
        }
        modules.clear()
    }

    private suspend fun cleanupOrphans() {
        val loadedModules = pipeWire.listShortModules()
        val orphanIds = loadedModules
            .filter { line -> line.contains("AudioRouter_") }
            .mapNotNull { line -> line.split("\t").getOrNull(0)?.toIntOrNull() }

        if (orphanIds.isNotEmpty()) {
            log.warn { "Found ${orphanIds.size} orphaned AudioRouter modules from previous session — cleaning up" }
            orphanIds.forEach { pipeWire.unloadModule(it) }
        }
    }

    fun isReady(): Boolean = modules.isNotEmpty()
}
