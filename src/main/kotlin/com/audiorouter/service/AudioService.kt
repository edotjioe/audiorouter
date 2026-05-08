package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream

/**
 * Platform-agnostic audio backend.
 *
 * LinuxAudioService  — PipeWire/PulseAudio via pactl (Linux)
 * WindowsAudioService — WASAPI via JNA + javax.sound (Windows)
 */
interface AudioService {

    // ── Device discovery ──────────────────────────────────────────────────
    suspend fun listRealSinks(): List<Pair<Int, String>>

    /** All sinks including virtual AudioRouter_ ones — used by RoutingEngine to map stream→channel. */
    suspend fun listAllSinks(): List<Pair<Int, String>>

    // ── Virtual channel lifecycle ─────────────────────────────────────────
    /** Create a virtual null-sink for [channel]. Returns a handle ≥ 0 on success, -1 on failure. */
    suspend fun loadNullSink(channel: AudioChannel): Int

    /** Wire [channel]'s virtual output to a real [outputSinkName]. Returns handle ≥ 0. */
    suspend fun loadLoopback(channel: AudioChannel, outputSinkName: String): Int

    /** Tear down a module/virtual device by handle. */
    suspend fun unloadModule(id: Int): Boolean

    /** List loaded low-level modules (tab-separated id/name lines on Linux, stubs on Windows). */
    suspend fun listShortModules(): List<String>

    // ── Stream management ─────────────────────────────────────────────────
    suspend fun listSinkInputs(): List<AudioStream>
    suspend fun moveSinkInput(sinkInputId: Int, sinkName: String): Boolean

    // ── Volume / mute ──────────────────────────────────────────────────────
    suspend fun setSinkVolume(sinkName: String, percent: Int): Boolean
    suspend fun setSinkMute(sinkName: String, muted: Boolean): Boolean

    // ── Real-time events ───────────────────────────────────────────────────
    /** Starts a long-running process/listener and returns it so StreamMonitor can read lines. */
    fun startSubscribeProcess(): Process

    // ── Level capture ──────────────────────────────────────────────────────
    /**
     * Opens an audio capture stream for [channel]'s output monitor.
     * Returns a [Process] whose stdout is raw interleaved s16le stereo PCM at 22050 Hz,
     * or null if the platform cannot provide a capture stream.
     */
    fun openLevelCapture(channel: AudioChannel): Process?
}
