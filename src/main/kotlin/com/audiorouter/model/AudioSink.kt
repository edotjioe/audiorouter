package com.audiorouter.model

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
