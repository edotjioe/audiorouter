package com.audiorouter.model

/**
 * The six virtual audio channels exposed by AudioRouter.
 *
 * Each channel maps to a PipeWire null-sink named `AudioRouter_<sinkSuffix>` on Linux,
 * a pre-configured BlackHole aggregate device on macOS, or a VB-Cable virtual input on Windows.
 *
 * @property displayName Human-readable label shown in the UI (e.g. "Game").
 * @property sinkSuffix  Suffix appended to "AudioRouter_" to form the sink name (e.g. "Game").
 */
enum class AudioChannel(val displayName: String, val sinkSuffix: String) {
    MASTER("Master", "Master"),
    GAME("Game", "Game"),
    CHAT("Chat", "Chat"),
    MEDIA("Media", "Media"),
    AUX("Aux", "Aux"),
    MIC("Mic", "Mic");

    /** Full sink identifier used in pactl/CoreAudio/WASAPI calls, e.g. `"AudioRouter_Game"`. */
    val sinkName: String get() = "AudioRouter_$sinkSuffix"

    companion object {
        /**
         * The five channels that have virtual sinks. [MASTER] is excluded because it
         * controls overall output level rather than owning its own sink.
         */
        val routingChannels get() = listOf(GAME, CHAT, MEDIA, AUX, MIC)
    }
}
