package com.audiorouter.model

import kotlinx.serialization.Serializable

/**
 * Immutable top-level configuration that is serialized to
 * `~/.config/AudioRouter/config.json` (Linux/macOS) or `%APPDATA%\AudioRouter\config.json` (Windows).
 *
 * All mutation methods return a new copy — use [ConfigRepository.update] to apply changes
 * and persist them atomically.
 *
 * @property version            Schema version; currently always 1.
 * @property outputSinkName     Default real output device for all channels without a per-channel override.
 * @property channelOutputSinks Per-channel output overrides keyed by [AudioChannel.name].
 *                              An empty or absent entry means the channel uses [outputSinkName].
 * @property channelVolumes     Volume (0–100) per channel, keyed by [AudioChannel.name]. Defaults to 100.
 * @property channelMutes       Mute state per channel, keyed by [AudioChannel.name]. Defaults to false.
 * @property appRules           Ordered list of application-to-channel routing rules.
 */
@Serializable
data class RoutingConfig(
    val version: Int = 1,
    val outputSinkName: String = "",
    val channelOutputSinks: Map<String, String> = emptyMap(),
    val channelVolumes: Map<String, Int> = AudioChannel.entries.associate { it.name to 100 },
    val channelMutes: Map<String, Boolean> = AudioChannel.entries.associate { it.name to false },
    val appRules: List<AppRule> = emptyList()
) {
    /** Returns the volume for [channel], defaulting to 100 if not set. */
    fun volumeFor(channel: AudioChannel): Int = channelVolumes[channel.name] ?: 100

    /** Returns the mute state for [channel], defaulting to false if not set. */
    fun mutedFor(channel: AudioChannel): Boolean = channelMutes[channel.name] ?: false

    /** Returns the [AppRule] for [appName], or null if no rule exists. */
    fun ruleFor(appName: String): AppRule? = appRules.firstOrNull { it.appName == appName }

    /** Resolves the [AudioChannel] for [appName] via [appRules], or null if unassigned. */
    fun channelFor(appName: String): AudioChannel? = ruleFor(appName)?.resolvedChannel()

    /**
     * Returns the effective output sink name for [channel].
     * Uses [channelOutputSinks] if a non-blank override exists, otherwise falls back to [outputSinkName].
     */
    fun outputSinkFor(channel: AudioChannel): String =
        channelOutputSinks[channel.name]?.takeIf { it.isNotBlank() } ?: outputSinkName

    /** Returns a copy with the volume for [channel] set to [percent] (clamped to 0–100). */
    fun withVolume(channel: AudioChannel, percent: Int) =
        copy(channelVolumes = channelVolumes + (channel.name to percent.coerceIn(0, 100)))

    /** Returns a copy with the mute state for [channel] set to [muted]. */
    fun withMute(channel: AudioChannel, muted: Boolean) =
        copy(channelMutes = channelMutes + (channel.name to muted))

    /** Returns a copy with [channel] routed to [sinkName] as its per-channel output device. */
    fun withChannelOutput(channel: AudioChannel, sinkName: String) =
        copy(channelOutputSinks = channelOutputSinks + (channel.name to sinkName))

    /**
     * Returns a copy with an [AppRule] mapping [appName] → [channel].
     * Replaces any existing rule for the same app.
     */
    fun withRule(appName: String, channel: AudioChannel): RoutingConfig {
        val updated = appRules.filter { it.appName != appName } + AppRule(appName, channel)
        return copy(appRules = updated)
    }

    /** Returns a copy with the rule for [appName] removed. No-op if no rule exists. */
    fun withoutRule(appName: String) =
        copy(appRules = appRules.filter { it.appName != appName })
}
