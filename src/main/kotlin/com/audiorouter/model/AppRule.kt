package com.audiorouter.model

import kotlinx.serialization.Serializable

/**
 * Persisted mapping from an application name to an [AudioChannel].
 *
 * The channel is stored as its enum name string (e.g. `"MEDIA"`) rather than the ordinal
 * so that reordering the enum in future does not corrupt saved configs.
 *
 * @property appName The application name as reported by the audio server (e.g. `"Firefox"`).
 * @property channel The enum name of the target [AudioChannel] (e.g. `"MEDIA"`).
 */
@Serializable
data class AppRule(
    val appName: String,
    val channel: String
) {
    /** Returns the [AudioChannel] for [channel], or null if the name no longer matches any entry. */
    fun resolvedChannel(): AudioChannel? = AudioChannel.entries.firstOrNull { it.name == channel }

    companion object {
        /** Convenience factory that accepts a typed [AudioChannel] instead of a raw string. */
        operator fun invoke(appName: String, channel: AudioChannel) =
            AppRule(appName, channel.name)
    }
}
