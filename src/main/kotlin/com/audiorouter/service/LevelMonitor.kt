package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

private val log = KotlinLogging.logger {}

internal fun computeStereoRms(buffer: ByteArray, validBytes: Int): Pair<Float, Float> {
    var sumL = 0.0; var sumR = 0.0; var frames = 0; var i = 0
    while (i + 3 < validBytes) {
        val l = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toFloat() / 32768f
        val r = ((buffer[i + 2].toInt() and 0xFF) or (buffer[i + 3].toInt() shl 8)).toShort().toFloat() / 32768f
        sumL += l * l; sumR += r * r; frames++; i += 4
    }
    return if (frames > 0)
        sqrt(sumL / frames).toFloat().coerceIn(0f, 1f) to sqrt(sumR / frames).toFloat().coerceIn(0f, 1f)
    else 0f to 0f
}

class LevelMonitor(private val audioService: AudioService, private val scope: CoroutineScope) {

    private val _levels: Map<AudioChannel, MutableStateFlow<Pair<Float, Float>>> =
        AudioChannel.entries.associateWith { MutableStateFlow(0f to 0f) }

    fun levelFlow(channel: AudioChannel): StateFlow<Pair<Float, Float>> = _levels[channel]!!

    fun start() {
        AudioChannel.routingChannels.forEach { channel ->
            scope.launch(Dispatchers.IO) { monitorChannel(channel) }
        }
    }

    private suspend fun monitorChannel(channel: AudioChannel) {
        // 4 bytes per stereo s16le frame; 2205 frames ≈ 100 ms at 22050 Hz
        val buffer = ByteArray(4410)

        while (currentCoroutineContext().isActive) {
            try {
                val proc = audioService.openLevelCapture(channel)
                if (proc == null) { delay(5000); continue }

                try {
                    val input = proc.inputStream
                    while (currentCoroutineContext().isActive) {
                        val read = input.read(buffer)
                        if (read < 4) break
                        val (rmsL, rmsR) = computeStereoRms(buffer, read)
                        if (rmsL > 0f || rmsR > 0f) _levels[channel]!!.value = rmsL to rmsR
                    }
                } finally {
                    proc.destroy()
                    _levels[channel]!!.value = 0f to 0f
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "Level monitor for ${channel.displayName} error, retrying: ${e.message}" }
                delay(2000)
            }
        }
    }
}
