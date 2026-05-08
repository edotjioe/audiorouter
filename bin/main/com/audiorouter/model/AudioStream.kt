package com.audiorouter.model

/**
 * Snapshot of a live audio stream (sink-input on Linux / audio session on Windows).
 *
 * @property sinkInputId     Platform sink-input index (pactl sink-input # on Linux, session index on Windows).
 * @property appName         Display name reported by the audio server (e.g. `"Firefox"`).
 * @property appBinary       Process binary name used for rule matching (e.g. `"firefox"`).
 * @property pid             Operating-system process ID of the producing application.
 * @property currentSinkId   Sink the stream is currently routed to; used to derive [assignedChannel].
 * @property assignedChannel The [AudioChannel] this stream is routed to, or null if unassigned.
 *                           Populated by [RoutingEngine] after correlating [currentSinkId] with known sinks.
 */
data class AudioStream(
    val sinkInputId: Int,
    val appName: String,
    val appBinary: String,
    val pid: Int,
    val currentSinkId: Int,
    val assignedChannel: AudioChannel?
)
