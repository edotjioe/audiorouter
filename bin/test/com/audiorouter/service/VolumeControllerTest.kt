package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.RoutingConfig
import com.audiorouter.persistence.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class VolumeControllerTest {

    private fun makeRepo(config: RoutingConfig = RoutingConfig()): ConfigRepository {
        val dir = Files.createTempDirectory("vctest")
        val repo = ConfigRepository(
            scope = CoroutineScope(SupervisorJob()),
            configDirOverride = dir
        )
        repo.load()
        // Seed the config
        repo.update { config }
        return repo
    }

    // ── setVolume ─────────────────────────────────────────────────────────

    @Test
    fun `setVolume clamps below 0 to 0`() {
        val fake = FakeAudioService()
        val controller = VolumeController(fake, makeRepo(), CoroutineScope(SupervisorJob()))
        controller.init()

        controller.setVolume(AudioChannel.GAME, -10)
        assertEquals(0, controller.volumeFlow(AudioChannel.GAME).value)
    }

    @Test
    fun `setVolume clamps above 100 to 100`() {
        val fake = FakeAudioService()
        val controller = VolumeController(fake, makeRepo(), CoroutineScope(SupervisorJob()))
        controller.init()

        controller.setVolume(AudioChannel.GAME, 150)
        assertEquals(100, controller.volumeFlow(AudioChannel.GAME).value)
    }

    @Test
    fun `setVolume stores value within range`() {
        val fake = FakeAudioService()
        val controller = VolumeController(fake, makeRepo(), CoroutineScope(SupervisorJob()))
        controller.init()

        controller.setVolume(AudioChannel.CHAT, 72)
        assertEquals(72, controller.volumeFlow(AudioChannel.CHAT).value)
    }

    @Test
    fun `setVolume does not affect other channels`() {
        val fake = FakeAudioService()
        val controller = VolumeController(fake, makeRepo(), CoroutineScope(SupervisorJob()))
        controller.init()

        controller.setVolume(AudioChannel.GAME, 40)
        assertEquals(100, controller.volumeFlow(AudioChannel.CHAT).value)
    }

    // ── setMute ───────────────────────────────────────────────────────────

    @Test
    fun `setMute stores true`() {
        val fake = FakeAudioService()
        val controller = VolumeController(fake, makeRepo(), CoroutineScope(SupervisorJob()))
        controller.init()

        controller.setMute(AudioChannel.GAME, true)
        assertTrue(controller.muteFlow(AudioChannel.GAME).value)
    }

    @Test
    fun `setMute can be toggled back to false`() {
        val fake = FakeAudioService()
        val controller = VolumeController(fake, makeRepo(), CoroutineScope(SupervisorJob()))
        controller.init()

        controller.setMute(AudioChannel.CHAT, true)
        controller.setMute(AudioChannel.CHAT, false)
        assertFalse(controller.muteFlow(AudioChannel.CHAT).value)
    }

    // ── toggleMute ────────────────────────────────────────────────────────

    @Test
    fun `toggleMute flips from false to true`() {
        val fake = FakeAudioService()
        val controller = VolumeController(fake, makeRepo(), CoroutineScope(SupervisorJob()))
        controller.init()

        controller.toggleMute(AudioChannel.MEDIA)
        assertTrue(controller.muteFlow(AudioChannel.MEDIA).value)
    }

    @Test
    fun `toggleMute flips from true to false`() {
        val fake = FakeAudioService()
        val config = RoutingConfig().withMute(AudioChannel.MEDIA, true)
        val controller = VolumeController(fake, makeRepo(config), CoroutineScope(SupervisorJob()))
        controller.init()

        controller.toggleMute(AudioChannel.MEDIA)
        assertFalse(controller.muteFlow(AudioChannel.MEDIA).value)
    }

    // ── init loads stored config ──────────────────────────────────────────

    @Test
    fun `init loads stored volume from config`() {
        val fake = FakeAudioService()
        val config = RoutingConfig().withVolume(AudioChannel.GAME, 42)
        val controller = VolumeController(fake, makeRepo(config), CoroutineScope(SupervisorJob()))
        controller.init()

        assertEquals(42, controller.volumeFlow(AudioChannel.GAME).value)
    }

    @Test
    fun `init loads stored mute from config`() {
        val fake = FakeAudioService()
        val config = RoutingConfig().withMute(AudioChannel.CHAT, true)
        val controller = VolumeController(fake, makeRepo(config), CoroutineScope(SupervisorJob()))
        controller.init()

        assertTrue(controller.muteFlow(AudioChannel.CHAT).value)
    }

    // ── applyStoredVolumes ────────────────────────────────────────────────

    @Test
    fun `applyStoredVolumes calls setSinkVolume for every channel`() = runTest {
        val fake = FakeAudioService()
        val config = RoutingConfig().withVolume(AudioChannel.GAME, 55)
        val controller = VolumeController(fake, makeRepo(config), CoroutineScope(SupervisorJob()))

        controller.applyStoredVolumes()

        val gameVolumeCall = fake.volumeChanges.firstOrNull { it.first == AudioChannel.GAME.sinkName }
        assertNotNull(gameVolumeCall)
        assertEquals(55, gameVolumeCall.second)
    }

    @Test
    fun `applyStoredVolumes calls setSinkMute for every channel`() = runTest {
        val fake = FakeAudioService()
        val config = RoutingConfig().withMute(AudioChannel.AUX, true)
        val controller = VolumeController(fake, makeRepo(config), CoroutineScope(SupervisorJob()))

        controller.applyStoredVolumes()

        val auxMuteCall = fake.muteChanges.firstOrNull { it.first == AudioChannel.AUX.sinkName }
        assertNotNull(auxMuteCall)
        assertTrue(auxMuteCall.second)
    }
}
