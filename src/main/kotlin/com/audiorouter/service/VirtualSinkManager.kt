package com.audiorouter.service

import com.audiorouter.model.AudioChannel
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

    private data class ModuleIds(val nullSink: Int, val loopback: Int)

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
            val loopbackId = if (outputSink.isNotBlank()) pipeWire.loadLoopback(channel, outputSink) else -1
            if (loopbackId < 0 && outputSink.isNotBlank()) {
                log.warn { "Failed to create loopback for ${channel.displayName}" }
            }
            modules[channel] = ModuleIds(nullSinkId, loopbackId)
            log.info { "Created virtual sink for ${channel.displayName}: null=$nullSinkId, loopback=$loopbackId → $outputSink" }
        }
    }

    /** Tears down and recreates the loopback for [channel] pointing at [newOutputSinkName]. */
    suspend fun updateChannelOutput(channel: AudioChannel, newOutputSinkName: String) {
        val ids = modules[channel] ?: return
        if (ids.loopback >= 0) pipeWire.unloadModule(ids.loopback)
        val newLoopbackId = if (newOutputSinkName.isNotBlank())
            pipeWire.loadLoopback(channel, newOutputSinkName) else -1
        modules[channel] = ids.copy(loopback = newLoopbackId)
        log.info { "Channel ${channel.displayName} output → $newOutputSinkName (loopback=$newLoopbackId)" }
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

    /** Unloads all virtual sinks and loopbacks created by this instance. */
    suspend fun cleanup() {
        for ((channel, ids) in modules.entries.toList()) {
            if (ids.loopback >= 0) pipeWire.unloadModule(ids.loopback)
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
