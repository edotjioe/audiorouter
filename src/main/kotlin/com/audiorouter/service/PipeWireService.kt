package com.audiorouter.service

import com.audiorouter.model.AudioStream
import com.audiorouter.model.AudioChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

class PipeWireService {

    private suspend fun exec(vararg args: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(10, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()
            if (!exited) {
                process.destroyForcibly()
                error("Command timed out: ${args.joinToString(" ")}")
            }
            output
        }.onFailure { log.error { "exec failed for ${args.joinToString(" ")}: ${it.message}" } }
    }

    suspend fun loadNullSink(channel: AudioChannel): Int {
        val sinkName = channel.sinkName
        val description = "AudioRouter ${channel.displayName}"
        val output = exec(
            "pactl", "load-module", "module-null-sink",
            "sink_name=$sinkName",
            "sink_properties=device.description=$description"
        ).getOrElse { return -1 }
        return output.trim().toIntOrNull() ?: run {
            log.error { "Failed to parse module ID from: $output" }
            -1
        }
    }

    suspend fun loadLoopback(channel: AudioChannel, outputSinkName: String): Int {
        val output = exec(
            "pactl", "load-module", "module-loopback",
            "source=${channel.sinkName}.monitor",
            "sink=$outputSinkName",
            "latency_msec=10"
        ).getOrElse { return -1 }
        return output.trim().toIntOrNull() ?: run {
            log.error { "Failed to parse loopback module ID from: $output" }
            -1
        }
    }

    suspend fun unloadModule(moduleId: Int): Boolean {
        if (moduleId < 0) return false
        return exec("pactl", "unload-module", moduleId.toString()).isSuccess
    }

    suspend fun listShortModules(): List<String> {
        val output = exec("pactl", "list", "short", "modules").getOrElse { return emptyList() }
        return output.lines().filter { it.isNotBlank() }
    }

    suspend fun listShortSinks(): List<Pair<Int, String>> {
        val output = exec("pactl", "list", "short", "sinks").getOrElse { return emptyList() }
        return output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("\t")
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val name = parts.getOrNull(1) ?: return@mapNotNull null
                id to name
            }
    }

    suspend fun listRealSinks(): List<Pair<Int, String>> =
        listShortSinks().filter { (_, name) -> !name.startsWith("AudioRouter_") }

    suspend fun listSinkInputs(): List<AudioStream> {
        val output = exec("pactl", "list", "sink-inputs").getOrElse { return emptyList() }
        return parseSinkInputs(output)
    }

    suspend fun getSinkInput(sinkInputId: Int): AudioStream? {
        val all = listSinkInputs()
        return all.firstOrNull { it.sinkInputId == sinkInputId }
    }

    suspend fun moveSinkInput(sinkInputId: Int, sinkName: String): Boolean {
        return exec("pactl", "move-sink-input", sinkInputId.toString(), sinkName).isSuccess
    }

    suspend fun setSinkVolume(sinkName: String, percent: Int): Boolean {
        val clamped = percent.coerceIn(0, 100)
        return exec("pactl", "set-sink-volume", sinkName, "$clamped%").isSuccess
    }

    suspend fun setSinkMute(sinkName: String, muted: Boolean): Boolean {
        return exec("pactl", "set-sink-mute", sinkName, if (muted) "1" else "0").isSuccess
    }

    suspend fun getSinkIdByName(name: String): Int? {
        return listShortSinks().firstOrNull { (_, n) -> n == name }?.first
    }

    fun startSubscribeProcess(): Process {
        return ProcessBuilder("pactl", "subscribe")
            .redirectErrorStream(true)
            .start()
    }

    private fun parseSinkInputs(output: String): List<AudioStream> {
        val blocks = output.split(Regex("(?=^Sink Input #)", RegexOption.MULTILINE))
        return blocks.mapNotNull { block ->
            if (block.isBlank()) return@mapNotNull null

            val idMatch = Regex("""^Sink Input #(\d+)""").find(block)
            val sinkInputId = idMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null

            val sinkId = Regex("""Sink:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val appName = Regex("""application\.name\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
            val appBinary = Regex("""application\.process\.binary\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
            val pid = Regex("""application\.process\.id\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            // Fallback for sandboxed apps that set neither application.name nor application.process.binary
            val mediaName = Regex("""media\.name\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
            val clientName = Regex("""node\.name\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""

            val displayName = appName.ifBlank { appBinary.ifBlank { mediaName.ifBlank { clientName } } }
            if (displayName.isBlank()) return@mapNotNull null

            AudioStream(
                sinkInputId = sinkInputId,
                appName = displayName,
                appBinary = appBinary,
                pid = pid,
                currentSinkId = sinkId,
                assignedChannel = null
            )
        }
    }
}
