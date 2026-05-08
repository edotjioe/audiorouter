package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Linux audio backend — delegates all operations to pactl / pacat (PipeWire or PulseAudio).
 * Both PipeWire (with its PulseAudio compat layer) and native PulseAudio expose the same
 * pactl API, so this implementation works on any modern Linux distribution.
 */
class LinuxAudioService : AudioService {

    // Detect the PulseAudio/PipeWire socket path so subprocesses can connect
    // even when PULSE_SERVER isn't inherited (e.g. Gradle daemon, systemd services).
    private val pulseServer: String? by lazy {
        System.getenv("PULSE_SERVER")
            ?: java.io.File("/run/flatpak/pulse/native").takeIf { it.exists() }?.let { "unix:${it.path}" }
            ?: System.getenv("XDG_RUNTIME_DIR")?.let { dir ->
                java.io.File("$dir/pulse/native").takeIf { it.exists() }?.let { "unix:${it.path}" }
            }
    }

    private fun audioProcess(vararg args: String): ProcessBuilder =
        ProcessBuilder(*args).redirectErrorStream(true).also { pb ->
            pulseServer?.let { pb.environment()["PULSE_SERVER"] = it }
        }

    private suspend fun exec(vararg args: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val process = audioProcess(*args).start()
            val exited = process.waitFor(10, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()
            if (!exited) { process.destroyForcibly(); error("Command timed out: ${args.joinToString(" ")}") }
            output
        }.onFailure { log.error { "exec failed for ${args.joinToString(" ")}: ${it.message}" } }
    }

    override suspend fun loadNullSink(channel: AudioChannel): Int {
        val output = exec(
            "pactl", "load-module", "module-null-sink",
            "sink_name=${channel.sinkName}",
            "sink_properties=device.description=AudioRouter ${channel.displayName}"
        ).getOrElse { return -1 }
        return output.trim().toIntOrNull() ?: run { log.error { "Bad module ID: $output" }; -1 }
    }

    override suspend fun loadLoopback(channel: AudioChannel, outputSinkName: String): Int {
        val output = exec(
            "pactl", "load-module", "module-loopback",
            "source=${channel.sinkName}.monitor",
            "sink=$outputSinkName",
            "latency_msec=10"
        ).getOrElse { return -1 }
        return output.trim().toIntOrNull() ?: run { log.error { "Bad loopback ID: $output" }; -1 }
    }

    override suspend fun unloadModule(id: Int): Boolean {
        if (id < 0) return false
        return exec("pactl", "unload-module", id.toString()).isSuccess
    }

    override suspend fun listShortModules(): List<String> {
        val output = exec("pactl", "list", "short", "modules").getOrElse { return emptyList() }
        return output.lines().filter { it.isNotBlank() }
    }

    override suspend fun listAllSinks(): List<Pair<Int, String>> {
        val output = exec("pactl", "list", "short", "sinks").getOrElse { return emptyList() }
        return output.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\t")
            val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val name = parts.getOrNull(1) ?: return@mapNotNull null
            id to name
        }
    }

    override suspend fun listRealSinks(): List<Pair<Int, String>> {
        val output = exec("pactl", "list", "short", "sinks").getOrElse { return emptyList() }
        return output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("\t")
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val name = parts.getOrNull(1) ?: return@mapNotNull null
                id to name
            }
            .filter { (_, name) -> !name.startsWith("AudioRouter_") }
    }

    override suspend fun listSinkInputs(): List<AudioStream> {
        val output = exec("pactl", "list", "sink-inputs").getOrElse { return emptyList() }
        val streams = parseSinkInputs(output)
        log.info { "listSinkInputs: ${output.length} chars → ${streams.size} streams: ${streams.map { it.appName }}" }
        return streams
    }

    override suspend fun moveSinkInput(sinkInputId: Int, sinkName: String): Boolean =
        exec("pactl", "move-sink-input", sinkInputId.toString(), sinkName).isSuccess

    override suspend fun setSinkVolume(sinkName: String, percent: Int): Boolean =
        exec("pactl", "set-sink-volume", sinkName, "${percent.coerceIn(0, 100)}%").isSuccess

    override suspend fun setSinkMute(sinkName: String, muted: Boolean): Boolean =
        exec("pactl", "set-sink-mute", sinkName, if (muted) "1" else "0").isSuccess

    override fun startSubscribeProcess(): Process =
        audioProcess("pactl", "subscribe").start()

    override fun openLevelCapture(channel: AudioChannel): Process =
        audioProcess(
            "pacat", "--record",
            "--device=${channel.sinkName}.monitor",
            "--format=s16le", "--rate=22050", "--channels=2", "--latency-msec=50"
        ).start()

    private fun parseSinkInputs(output: String): List<AudioStream> {
        val blocks = output.split(Regex("(?=^Sink Input #)", RegexOption.MULTILINE))
        return blocks.mapNotNull { block ->
            if (block.isBlank()) return@mapNotNull null
            val sinkInputId = Regex("""^Sink Input #(\d+)""").find(block)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val sinkId = Regex("""Sink:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val appName = Regex("""application\.name\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
            val appBinary = Regex("""application\.process\.binary\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
            val pid = Regex("""application\.process\.id\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val mediaName = Regex("""media\.name\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
            val nodeName = Regex("""node\.name\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
            val displayName = appName.ifBlank { appBinary.ifBlank { mediaName.ifBlank { nodeName } } }
            if (displayName.isBlank()) return@mapNotNull null
            AudioStream(sinkInputId, displayName, appBinary, pid, sinkId, null)
        }
    }
}
