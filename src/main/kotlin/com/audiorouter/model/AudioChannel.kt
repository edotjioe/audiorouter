package com.audiorouter.model

enum class AudioChannel(val displayName: String, val sinkSuffix: String) {
    MASTER("Master", "Master"),
    GAME("Game", "Game"),
    CHAT("Chat", "Chat"),
    MEDIA("Media", "Media"),
    AUX("Aux", "Aux"),
    MIC("Mic", "Mic");

    val sinkName: String get() = "AudioRouter_$sinkSuffix"

    companion object {
        val routingChannels get() = listOf(GAME, CHAT, MEDIA, AUX, MIC)
    }
}
