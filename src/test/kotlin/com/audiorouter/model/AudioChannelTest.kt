package com.audiorouter.model

import kotlin.test.*

class AudioChannelTest {

    @Test
    fun `sinkName is prefixed with AudioRouter_`() {
        assertEquals("AudioRouter_Game", AudioChannel.GAME.sinkName)
        assertEquals("AudioRouter_Chat", AudioChannel.CHAT.sinkName)
        assertEquals("AudioRouter_Media", AudioChannel.MEDIA.sinkName)
        assertEquals("AudioRouter_Aux", AudioChannel.AUX.sinkName)
        assertEquals("AudioRouter_Mic", AudioChannel.MIC.sinkName)
        assertEquals("AudioRouter_Master", AudioChannel.MASTER.sinkName)
    }

    @Test
    fun `displayName matches expected labels`() {
        assertEquals("Master", AudioChannel.MASTER.displayName)
        assertEquals("Game", AudioChannel.GAME.displayName)
        assertEquals("Chat", AudioChannel.CHAT.displayName)
        assertEquals("Media", AudioChannel.MEDIA.displayName)
        assertEquals("Aux", AudioChannel.AUX.displayName)
        assertEquals("Mic", AudioChannel.MIC.displayName)
    }

    @Test
    fun `routingChannels excludes MASTER`() {
        val routing = AudioChannel.routingChannels
        assertFalse(AudioChannel.MASTER in routing)
        assertTrue(AudioChannel.GAME in routing)
        assertTrue(AudioChannel.CHAT in routing)
        assertTrue(AudioChannel.MEDIA in routing)
        assertTrue(AudioChannel.AUX in routing)
        assertTrue(AudioChannel.MIC in routing)
    }

    @Test
    fun `routingChannels has exactly 5 entries`() {
        assertEquals(5, AudioChannel.routingChannels.size)
    }

    @Test
    fun `entries contains all 6 channels`() {
        assertEquals(6, AudioChannel.entries.size)
    }
}
