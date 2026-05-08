package com.audiorouter.model

data class AudioStream(
    val sinkInputId: Int,
    val appName: String,
    val appBinary: String,
    val pid: Int,
    val currentSinkId: Int,
    val assignedChannel: AudioChannel?
)
