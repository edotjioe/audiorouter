package com.audiorouter.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private val log = KotlinLogging.logger {}

/** Events emitted by [StreamMonitor] when the audio-server state changes. */
sealed class AudioEvent {
    /** A new sink-input (audio stream) with the given [sinkInputId] has appeared. */
    data class SinkInputAdded(val sinkInputId: Int) : AudioEvent()
    /** The sink-input with [sinkInputId] has been removed (application closed or paused). */
    data class SinkInputRemoved(val sinkInputId: Int) : AudioEvent()
    /** One or more audio sinks changed (device added/removed or loopback target updated). */
    data object SinkChanged : AudioEvent()
    /** The subscription process died and was restarted; all stream state should be refreshed. */
    data object AllStreamsRefresh : AudioEvent()
}

/**
 * Monitors the audio server for stream and device changes, emitting [AudioEvent]s on [events].
 *
 * On Linux this runs a long-lived `pactl subscribe` process and parses its stdout.
 * On Windows it reads periodic tick lines from a PowerShell polling script.
 * On macOS it receives ticks from [MacAudioService]'s CoreAudio device-change poller.
 *
 * If the subscription process exits unexpectedly, the monitor re-launches it with exponential
 * backoff (500 ms → 5 s) and emits [AudioEvent.AllStreamsRefresh] so consumers can reconcile state.
 *
 * @param pipeWire  The platform audio backend that supplies the subscription process.
 * @param scope     Coroutine scope used to host the monitor job.
 */
open class StreamMonitor(
    private val pipeWire: AudioService,
    private val scope: CoroutineScope
) {
    private val _events = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 64)
    open val events: SharedFlow<AudioEvent> = _events.asSharedFlow()

    private var monitorJob: Job? = null

    open fun start() {
        monitorJob = scope.launch {
            var backoffMs = 500L
            while (isActive) {
                try {
                    runSubscribeLoop()
                    backoffMs = 500L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn { "pactl subscribe failed: ${e.message} — retrying in ${backoffMs}ms" }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(5000L)
                    _events.emit(AudioEvent.AllStreamsRefresh)
                }
            }
        }
    }

    open fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private suspend fun runSubscribeLoop() = withContext(Dispatchers.IO) {
        val process = pipeWire.startSubscribeProcess()
        try {
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!isActive) break
                    val l = line ?: continue
                    parseEvent(l)?.let { _events.emit(it) }
                }
            }
        } finally {
            process.destroyForcibly()
        }
    }

    internal fun parseEvent(line: String): AudioEvent? {
        // pactl subscribe output: "Event 'new' on sink-input #1234"
        val idRegex = Regex("""#(\d+)""")
        return when {
            line.contains("'new' on sink-input") -> {
                val id = idRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                AudioEvent.SinkInputAdded(id)
            }
            line.contains("'remove' on sink-input") -> {
                val id = idRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                AudioEvent.SinkInputRemoved(id)
            }
            line.contains("on sink #") -> AudioEvent.SinkChanged
            else -> null
        }
    }
}
