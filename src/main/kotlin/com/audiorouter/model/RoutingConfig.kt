package com.audiorouter.model

import kotlinx.serialization.Serializable

@Serializable
data class RoutingConfig(
    val version: Int = 1,
    val outputSinkName: String = "",
    val channelOutputSinks: Map<String, String> = emptyMap(),
    val channelVolumes: Map<String, Int> = AudioChannel.entries.associate { it.name to 100 },
    val channelMutes: Map<String, Boolean> = AudioChannel.entries.associate { it.name to false },
    val appRules: List<AppRule> = emptyList()
) {
    fun volumeFor(channel: AudioChannel): Int = channelVolumes[channel.name] ?: 100
    fun mutedFor(channel: AudioChannel): Boolean = channelMutes[channel.name] ?: false
    fun ruleFor(appName: String): AppRule? = appRules.firstOrNull { it.appName == appName }
    fun channelFor(appName: String): AudioChannel? = ruleFor(appName)?.resolvedChannel()
    fun outputSinkFor(channel: AudioChannel): String =
        channelOutputSinks[channel.name]?.takeIf { it.isNotBlank() } ?: outputSinkName

    fun withVolume(channel: AudioChannel, percent: Int) =
        copy(channelVolumes = channelVolumes + (channel.name to percent.coerceIn(0, 100)))

    fun withMute(channel: AudioChannel, muted: Boolean) =
        copy(channelMutes = channelMutes + (channel.name to muted))

    fun withChannelOutput(channel: AudioChannel, sinkName: String) =
        copy(channelOutputSinks = channelOutputSinks + (channel.name to sinkName))

    fun withRule(appName: String, channel: AudioChannel): RoutingConfig {
        val updated = appRules.filter { it.appName != appName } + AppRule(appName, channel)
        return copy(appRules = updated)
    }

    fun withoutRule(appName: String) =
        copy(appRules = appRules.filter { it.appName != appName })
}
