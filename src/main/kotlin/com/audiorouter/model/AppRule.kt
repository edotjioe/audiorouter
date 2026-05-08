package com.audiorouter.model

import kotlinx.serialization.Serializable

@Serializable
data class AppRule(
    val appName: String,
    val channel: String  // stored as enum name string for JSON stability
) {
    fun resolvedChannel(): AudioChannel? = AudioChannel.entries.firstOrNull { it.name == channel }

    companion object {
        operator fun invoke(appName: String, channel: AudioChannel) =
            AppRule(appName, channel.name)
    }
}
