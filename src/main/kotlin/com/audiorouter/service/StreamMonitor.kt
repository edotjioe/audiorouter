package com.audiorouter.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private val log = KotlinLogging.logger {}

sealed class AudioEvent {
    data class SinkInputAdded(val sinkInputId: Int) : AudioEvent()
    data class SinkInputRemoved(val sinkInputId: Int) : AudioEvent()
    data object SinkChanged : AudioEvent()
    data object AllStreamsRefresh : AudioEvent()
}

class StreamMonitor(
    private val pipeWire: AudioService,
    private val scope: CoroutineScope
) {
    private val _events = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AudioEvent> = _events.asSharedFlow()

    private var monitorJob: Job? = null

    fun start() {
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

    fun stop() {
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

    private fun parseEvent(line: String): AudioEvent? {
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
