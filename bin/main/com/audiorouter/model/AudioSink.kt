package com.audiorouter.model

/**
 * Snapshot of a virtual audio sink managed by [VirtualSinkManager].
 *
 * @property id               Platform-specific sink identifier (pactl sink index on Linux).
 * @property name             Sink name as registered with the audio server (e.g. `"AudioRouter_Game"`).
 * @property description      Human-readable description shown in device pickers.
 * @property channel          The [AudioChannel] this sink belongs to.
 * @property volumePercent    Current volume in the range 0–100.
 * @property muted            Whether the sink is currently muted.
 * @property nullSinkModuleId Module handle for the null-sink (used to unload on shutdown).
 * @property loopbackModuleId Module handle for the loopback to the real output device.
 */
data class AudioSink(
    val id: Int,
    val name: String,
    val description: String,
    val channel: AudioChannel,
    val volumePercent: Int,
    val muted: Boolean,
    val nullSinkModuleId: Int,
    val loopbackModuleId: Int
)
