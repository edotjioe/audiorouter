package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.RoutingConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VirtualSinkManagerTest {

    // ── initialize ────────────────────────────────────────────────────────

    @Test
    fun `initialize creates one null-sink per routing channel`() = runTest {
        val fake = FakeAudioService()
        val manager = VirtualSinkManager(fake)

        manager.initialize(RoutingConfig(outputSinkName = "speakers"))

        assertEquals(AudioChannel.routingChannels.toSet(), fake.loadedNullSinks.toSet())
    }

    @Test
    fun `initialize creates one loopback per routing channel when output is set`() = runTest {
        val fake = FakeAudioService()
        val manager = VirtualSinkManager(fake)

        manager.initialize(RoutingConfig(outputSinkName = "speakers"))

        assertEquals(AudioChannel.routingChannels.size, fake.loadedLoopbacks.size)
        fake.loadedLoopbacks.forEach { (_, sink) -> assertEquals("speakers", sink) }
    }

    @Test
    fun `initialize skips loopback when outputSinkName is blank`() = runTest {
        val fake = FakeAudioService()
        val manager = VirtualSinkManager(fake)

        manager.initialize(RoutingConfig(outputSinkName = ""))

        assertTrue(fake.loadedLoopbacks.isEmpty())
    }

    @Test
    fun `initialize cleans up orphaned AudioRouter modules first`() = runTest {
        val fake = FakeAudioService().apply {
            shortModules = listOf(
                "42\tmodule-null-sink\tsink_name=AudioRouter_Game",
                "43\tmodule-loopback\t"
            )
        }
        val manager = VirtualSinkManager(fake)

        manager.initialize(RoutingConfig(outputSinkName = "speakers"))

        assertTrue(42 in fake.unloadedModules, "Should have unloaded orphan module 42")
    }

    @Test
    fun `isReady returns false before initialize`() {
        val manager = VirtualSinkManager(FakeAudioService())
        assertFalse(manager.isReady())
    }

    @Test
    fun `isReady returns true after initialize`() = runTest {
        val fake = FakeAudioService()
        val manager = VirtualSinkManager(fake)

        manager.initialize(RoutingConfig(outputSinkName = "speakers"))

        assertTrue(manager.isReady())
    }

    // ── updateChannelOutput ───────────────────────────────────────────────

    @Test
    fun `updateChannelOutput reloads loopback with new sink`() = runTest {
        val fake = FakeAudioService()
        val manager = VirtualSinkManager(fake)
        manager.initialize(RoutingConfig(outputSinkName = "speakers"))

        fake.loadedLoopbacks.clear()
        manager.updateChannelOutput(AudioChannel.GAME, "headset")

        assertEquals(1, fake.loadedLoopbacks.size)
        assertEquals(AudioChannel.GAME to "headset", fake.loadedLoopbacks.first())
    }

    @Test
    fun `updateChannelOutput with blank name unloads loopback without reloading`() = runTest {
        val fake = FakeAudioService()
        val manager = VirtualSinkManager(fake)
        manager.initialize(RoutingConfig(outputSinkName = "speakers"))

        val loopbackCountBefore = fake.unloadedModules.size
        manager.updateChannelOutput(AudioChannel.GAME, "")

        // Should have unloaded one module (the old loopback)
        assertTrue(fake.unloadedModules.size > loopbackCountBefore)
        // No new loopback should be loaded for GAME
        assertFalse(fake.loadedLoopbacks.any { it.first == AudioChannel.GAME && it.second.isBlank().not() && fake.loadedLoopbacks.indexOf(it) >= AudioChannel.routingChannels.size })
    }

    // ── cleanup ───────────────────────────────────────────────────────────

    @Test
    fun `cleanup unloads all modules`() = runTest {
        val fake = FakeAudioService()
        val manager = VirtualSinkManager(fake)
        manager.initialize(RoutingConfig(outputSinkName = "speakers"))

        fake.unloadedModules.clear()
        manager.cleanup()

        // 5 channels × (1 null-sink + 1 loopback) = 10 unloads
        assertEquals(AudioChannel.routingChannels.size * 2, fake.unloadedModules.size)
    }

    @Test
    fun `isReady returns false after cleanup`() = runTest {
        val fake = FakeAudioService()
        val manager = VirtualSinkManager(fake)
        manager.initialize(RoutingConfig(outputSinkName = "speakers"))
        manager.cleanup()

        assertFalse(manager.isReady())
    }
}
