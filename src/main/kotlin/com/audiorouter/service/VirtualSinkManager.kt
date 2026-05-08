package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.RoutingConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

class VirtualSinkManager(private val pipeWire: AudioService) {

    private data class ModuleIds(val nullSink: Int, val loopback: Int)

    private val modules = mutableMapOf<AudioChannel, ModuleIds>()

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

    suspend fun updateChannelOutput(channel: AudioChannel, newOutputSinkName: String) {
        val ids = modules[channel] ?: return
        if (ids.loopback >= 0) pipeWire.unloadModule(ids.loopback)
        val newLoopbackId = if (newOutputSinkName.isNotBlank())
            pipeWire.loadLoopback(channel, newOutputSinkName) else -1
        modules[channel] = ids.copy(loopback = newLoopbackId)
        log.info { "Channel ${channel.displayName} output → $newOutputSinkName (loopback=$newLoopbackId)" }
    }

    suspend fun updateDefaultOutput(newOutputSinkName: String, config: RoutingConfig) {
        // Only update channels that don't have a per-channel override
        for (channel in AudioChannel.routingChannels) {
            if (config.channelOutputSinks[channel.name].isNullOrBlank()) {
                updateChannelOutput(channel, newOutputSinkName)
            }
        }
    }

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
