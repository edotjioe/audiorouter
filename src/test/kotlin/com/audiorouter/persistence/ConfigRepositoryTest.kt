package com.audiorouter.persistence

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.RoutingConfig
import com.audiorouter.persistence.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class ConfigRepositoryTest {

    private fun tempRepo(): ConfigRepository {
        val dir = Files.createTempDirectory("configtest")
        return ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
    }

    // ── load ──────────────────────────────────────────────────────────────

    @Test
    fun `load returns default config when no file exists`() {
        val repo = tempRepo()
        repo.load()
        assertEquals(RoutingConfig(), repo.config.value)
    }

    @Test
    fun `load reads existing config file`() {
        val dir = Files.createTempDirectory("configtest2")
        dir.resolve("config.json").toFile().writeText(
            """{"version":1,"outputSinkName":"speakers","channelOutputSinks":{},"channelVolumes":{"MASTER":100,"GAME":80,"CHAT":100,"MEDIA":100,"AUX":100,"MIC":100},"channelMutes":{"MASTER":false,"GAME":false,"CHAT":false,"MEDIA":false,"AUX":false,"MIC":false},"appRules":[]}"""
        )
        val repo = ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
        repo.load()

        assertEquals("speakers", repo.config.value.outputSinkName)
        assertEquals(80, repo.config.value.volumeFor(AudioChannel.GAME))
    }

    @Test
    fun `load falls back to defaults on corrupt file`() {
        val dir = Files.createTempDirectory("configtest3")
        dir.resolve("config.json").toFile().writeText("not valid json {{{{")
        val repo = ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
        repo.load()

        assertEquals(RoutingConfig(), repo.config.value)
    }

    // ── update ────────────────────────────────────────────────────────────

    @Test
    fun `update mutates in-memory config immediately`() {
        val repo = tempRepo()
        repo.load()

        repo.update { it.withVolume(AudioChannel.GAME, 55) }

        assertEquals(55, repo.config.value.volumeFor(AudioChannel.GAME))
    }

    @Test
    fun `update does not affect other channels`() {
        val repo = tempRepo()
        repo.load()

        repo.update { it.withVolume(AudioChannel.GAME, 55) }

        assertEquals(100, repo.config.value.volumeFor(AudioChannel.CHAT))
    }

    @Test
    fun `multiple updates are applied in order`() {
        val repo = tempRepo()
        repo.load()

        repo.update { it.withVolume(AudioChannel.GAME, 55) }
        repo.update { it.withVolume(AudioChannel.GAME, 80) }

        assertEquals(80, repo.config.value.volumeFor(AudioChannel.GAME))
    }

    // ── flushImmediately + round-trip ─────────────────────────────────────

    @Test
    fun `flushImmediately writes config to disk`() = runTest {
        val dir = Files.createTempDirectory("configtest4")
        val repo = ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
        repo.load()
        repo.update { it.withVolume(AudioChannel.MEDIA, 42) }

        repo.flushImmediately()

        assertTrue(dir.resolve("config.json").toFile().exists(), "config.json should exist after flush")
    }

    @Test
    fun `flushed config can be reloaded with correct values`() = runTest {
        val dir = Files.createTempDirectory("configtest5")
        val repo1 = ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
        repo1.load()
        repo1.update { it.withVolume(AudioChannel.AUX, 33).withMute(AudioChannel.CHAT, true) }
        repo1.flushImmediately()

        val repo2 = ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
        repo2.load()

        assertEquals(33, repo2.config.value.volumeFor(AudioChannel.AUX))
        assertTrue(repo2.config.value.mutedFor(AudioChannel.CHAT))
    }

    @Test
    fun `app rules survive a flush-reload cycle`() = runTest {
        val dir = Files.createTempDirectory("configtest6")
        val repo1 = ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
        repo1.load()
        repo1.update { it.withRule("Firefox", AudioChannel.MEDIA).withRule("Discord", AudioChannel.CHAT) }
        repo1.flushImmediately()

        val repo2 = ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
        repo2.load()

        assertEquals(AudioChannel.MEDIA, repo2.config.value.channelFor("Firefox"))
        assertEquals(AudioChannel.CHAT, repo2.config.value.channelFor("Discord"))
    }

    @Test
    fun `per-channel output overrides survive a flush-reload cycle`() = runTest {
        val dir = Files.createTempDirectory("configtest7")
        val repo1 = ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
        repo1.load()
        repo1.update { it.withChannelOutput(AudioChannel.GAME, "headset") }
        repo1.flushImmediately()

        val repo2 = ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
        repo2.load()

        assertEquals("headset", repo2.config.value.outputSinkFor(AudioChannel.GAME))
    }
}
