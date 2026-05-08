package com.audiorouter.model

import kotlin.test.*

class RoutingConfigTest {

    // ── outputSinkFor ─────────────────────────────────────────────────────

    @Test
    fun `outputSinkFor returns per-channel override when set`() {
        val config = RoutingConfig(
            outputSinkName = "global",
            channelOutputSinks = mapOf("GAME" to "headset")
        )
        assertEquals("headset", config.outputSinkFor(AudioChannel.GAME))
    }

    @Test
    fun `outputSinkFor falls back to global when channel override is absent`() {
        val config = RoutingConfig(outputSinkName = "global")
        assertEquals("global", config.outputSinkFor(AudioChannel.CHAT))
    }

    @Test
    fun `outputSinkFor falls back to global when channel override is blank`() {
        val config = RoutingConfig(
            outputSinkName = "global",
            channelOutputSinks = mapOf("GAME" to "")
        )
        assertEquals("global", config.outputSinkFor(AudioChannel.GAME))
    }

    @Test
    fun `outputSinkFor returns empty string when both are blank`() {
        val config = RoutingConfig(outputSinkName = "")
        assertEquals("", config.outputSinkFor(AudioChannel.GAME))
    }

    // ── volume ────────────────────────────────────────────────────────────

    @Test
    fun `volumeFor returns 100 by default`() {
        assertEquals(100, RoutingConfig().volumeFor(AudioChannel.MASTER))
    }

    @Test
    fun `withVolume stores the new value`() {
        val config = RoutingConfig().withVolume(AudioChannel.GAME, 75)
        assertEquals(75, config.volumeFor(AudioChannel.GAME))
    }

    @Test
    fun `withVolume clamps below zero to zero`() {
        val config = RoutingConfig().withVolume(AudioChannel.GAME, -5)
        assertEquals(0, config.volumeFor(AudioChannel.GAME))
    }

    @Test
    fun `withVolume clamps above 100 to 100`() {
        val config = RoutingConfig().withVolume(AudioChannel.GAME, 150)
        assertEquals(100, config.volumeFor(AudioChannel.GAME))
    }

    @Test
    fun `withVolume does not affect other channels`() {
        val config = RoutingConfig().withVolume(AudioChannel.GAME, 50)
        assertEquals(100, config.volumeFor(AudioChannel.CHAT))
    }

    // ── mute ──────────────────────────────────────────────────────────────

    @Test
    fun `mutedFor returns false by default`() {
        assertFalse(RoutingConfig().mutedFor(AudioChannel.MASTER))
    }

    @Test
    fun `withMute stores true`() {
        val config = RoutingConfig().withMute(AudioChannel.CHAT, true)
        assertTrue(config.mutedFor(AudioChannel.CHAT))
    }

    @Test
    fun `withMute can be toggled back to false`() {
        val config = RoutingConfig().withMute(AudioChannel.CHAT, true).withMute(AudioChannel.CHAT, false)
        assertFalse(config.mutedFor(AudioChannel.CHAT))
    }

    // ── app rules ─────────────────────────────────────────────────────────

    @Test
    fun `channelFor returns null when no rule exists`() {
        assertNull(RoutingConfig().channelFor("Firefox"))
    }

    @Test
    fun `withRule adds a rule and channelFor resolves it`() {
        val config = RoutingConfig().withRule("Firefox", AudioChannel.MEDIA)
        assertEquals(AudioChannel.MEDIA, config.channelFor("Firefox"))
    }

    @Test
    fun `withRule replaces existing rule for the same app`() {
        val config = RoutingConfig()
            .withRule("Firefox", AudioChannel.GAME)
            .withRule("Firefox", AudioChannel.MEDIA)
        assertEquals(AudioChannel.MEDIA, config.channelFor("Firefox"))
        assertEquals(1, config.appRules.size)
    }

    @Test
    fun `withoutRule removes the rule`() {
        val config = RoutingConfig().withRule("Firefox", AudioChannel.MEDIA).withoutRule("Firefox")
        assertNull(config.channelFor("Firefox"))
    }

    @Test
    fun `withoutRule is a no-op when rule does not exist`() {
        val config = RoutingConfig().withoutRule("NonExistent")
        assertTrue(config.appRules.isEmpty())
    }

    @Test
    fun `withRule for different apps are independent`() {
        val config = RoutingConfig()
            .withRule("Firefox", AudioChannel.MEDIA)
            .withRule("Discord", AudioChannel.CHAT)
        assertEquals(AudioChannel.MEDIA, config.channelFor("Firefox"))
        assertEquals(AudioChannel.CHAT, config.channelFor("Discord"))
        assertEquals(2, config.appRules.size)
    }

    // ── channel output ────────────────────────────────────────────────────

    @Test
    fun `withChannelOutput stores the output and outputSinkFor returns it`() {
        val config = RoutingConfig().withChannelOutput(AudioChannel.GAME, "headset")
        assertEquals("headset", config.outputSinkFor(AudioChannel.GAME))
    }

    @Test
    fun `withChannelOutput does not affect other channels`() {
        val config = RoutingConfig(outputSinkName = "speakers")
            .withChannelOutput(AudioChannel.GAME, "headset")
        assertEquals("speakers", config.outputSinkFor(AudioChannel.CHAT))
    }
}
