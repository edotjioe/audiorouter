package com.audiorouter.model

import kotlin.test.*

class AppRuleTest {

    @Test
    fun `invoke factory stores channel as enum name string`() {
        val rule = AppRule("Firefox", AudioChannel.MEDIA)
        assertEquals("Firefox", rule.appName)
        assertEquals("MEDIA", rule.channel)
    }

    @Test
    fun `resolvedChannel returns correct AudioChannel`() {
        val rule = AppRule("Discord", AudioChannel.CHAT)
        assertEquals(AudioChannel.CHAT, rule.resolvedChannel())
    }

    @Test
    fun `resolvedChannel returns null for unknown channel string`() {
        val rule = AppRule(appName = "App", channel = "UNKNOWN_CHANNEL")
        assertNull(rule.resolvedChannel())
    }

    @Test
    fun `resolvedChannel returns null for blank channel string`() {
        val rule = AppRule(appName = "App", channel = "")
        assertNull(rule.resolvedChannel())
    }

    @Test
    fun `data class equality is value-based`() {
        val a = AppRule("Firefox", AudioChannel.GAME)
        val b = AppRule("Firefox", AudioChannel.GAME)
        assertEquals(a, b)
    }

    @Test
    fun `rules with different apps are not equal`() {
        val a = AppRule("Firefox", AudioChannel.GAME)
        val b = AppRule("Chrome", AudioChannel.GAME)
        assertNotEquals(a, b)
    }

    @Test
    fun `rules with different channels are not equal`() {
        val a = AppRule("Firefox", AudioChannel.GAME)
        val b = AppRule("Firefox", AudioChannel.MEDIA)
        assertNotEquals(a, b)
    }
}
