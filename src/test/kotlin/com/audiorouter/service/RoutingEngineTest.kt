package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream
import com.audiorouter.model.RoutingConfig
import com.audiorouter.persistence.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingEngineTest {

    private fun makeRepo(config: RoutingConfig = RoutingConfig()): ConfigRepository {
        val dir = Files.createTempDirectory("retest")
        val repo = ConfigRepository(CoroutineScope(SupervisorJob()), configDirOverride = dir)
        repo.load()
        repo.update { config }
        return repo
    }

    private fun stream(
        id: Int = 1,
        appName: String = "TestApp",
        binary: String = "testapp",
        sinkId: Int = 0,
        channel: AudioChannel? = null
    ) = AudioStream(id, appName, binary, pid = 1000, currentSinkId = sinkId, assignedChannel = channel)

    // ── assignStream ──────────────────────────────────────────────────────

    @Test
    fun `assignStream moves stream to channel sink`() = runTest {
        val fake = FakeAudioService()
        val monitor = FakeStreamMonitor(this)
        val engine = RoutingEngine(monitor, fake, makeRepo(), this)
        engine.start()

        engine.assignStream(stream(id = 5, appName = "Firefox"), AudioChannel.MEDIA)
        advanceUntilIdle()
        engine.stop()

        assertTrue(fake.movedStreams.any { it.first == 5 && it.second == AudioChannel.MEDIA.sinkName })
    }

    @Test
    fun `assignStream saves app rule to config`() = runTest {
        val fake = FakeAudioService()
        val monitor = FakeStreamMonitor(this)
        val repo = makeRepo()
        val engine = RoutingEngine(monitor, fake, repo, this)
        engine.start()

        engine.assignStream(stream(appName = "Discord"), AudioChannel.CHAT)
        advanceUntilIdle()
        engine.stop()

        assertEquals(AudioChannel.CHAT, repo.config.value.channelFor("Discord"))
    }

    // ── unassignStream ────────────────────────────────────────────────────

    @Test
    fun `unassignStream removes app rule from config`() = runTest {
        val fake = FakeAudioService()
        val repo = makeRepo(RoutingConfig().withRule("Firefox", AudioChannel.MEDIA))
        val monitor = FakeStreamMonitor(this)
        val engine = RoutingEngine(monitor, fake, repo, this)
        engine.start()

        engine.unassignStream(stream(appName = "Firefox"))
        advanceUntilIdle()
        engine.stop()

        assertNull(repo.config.value.channelFor("Firefox"))
    }

    @Test
    fun `unassignStream moves stream to global output sink`() = runTest {
        val fake = FakeAudioService()
        val repo = makeRepo(
            RoutingConfig(outputSinkName = "speakers").withRule("Firefox", AudioChannel.MEDIA)
        )
        val monitor = FakeStreamMonitor(this)
        val engine = RoutingEngine(monitor, fake, repo, this)
        engine.start()

        engine.unassignStream(stream(id = 3, appName = "Firefox"))
        advanceUntilIdle()
        engine.stop()

        assertTrue(fake.movedStreams.any { it.first == 3 && it.second == "speakers" })
    }

    // ── stream enrichment ─────────────────────────────────────────────────

    @Test
    fun `streams are enriched with channel when on an AudioRouter sink`() = runTest {
        val fake = FakeAudioService().apply {
            allSinks = listOf(10 to "AudioRouter_Game", 11 to "speakers")
            sinkInputs = listOf(stream(id = 1, sinkId = 10))
        }
        val monitor = FakeStreamMonitor(this)
        val engine = RoutingEngine(monitor, fake, makeRepo(), this)
        engine.start()
        advanceUntilIdle()
        engine.stop()

        val found = engine.streams.value.firstOrNull { it.sinkInputId == 1 }
        assertEquals(AudioChannel.GAME, found?.assignedChannel)
    }

    @Test
    fun `streams on non-AudioRouter sinks have null assignedChannel`() = runTest {
        val fake = FakeAudioService().apply {
            allSinks = listOf(11 to "speakers")
            sinkInputs = listOf(stream(id = 2, sinkId = 11))
        }
        val monitor = FakeStreamMonitor(this)
        val engine = RoutingEngine(monitor, fake, makeRepo(), this)
        engine.start()
        advanceUntilIdle()
        engine.stop()

        val found = engine.streams.value.firstOrNull { it.sinkInputId == 2 }
        assertNull(found?.assignedChannel)
    }

    // ── SinkInputRemoved ──────────────────────────────────────────────────

    @Test
    fun `SinkInputRemoved removes stream from list`() = runTest {
        val fake = FakeAudioService().apply {
            allSinks = emptyList()
            sinkInputs = listOf(stream(id = 7))
        }
        val monitor = FakeStreamMonitor(this)
        val engine = RoutingEngine(monitor, fake, makeRepo(), this)
        engine.start()
        advanceUntilIdle()

        monitor.emit(AudioEvent.SinkInputRemoved(7))
        advanceUntilIdle()
        engine.stop()

        assertFalse(engine.streams.value.any { it.sinkInputId == 7 })
    }

    // ── startup rules ─────────────────────────────────────────────────────

    @Test
    fun `existing stream is auto-routed on startup when rule matches`() = runTest {
        val fake = FakeAudioService().apply {
            allSinks = listOf(10 to "AudioRouter_Media", 11 to "speakers")
            sinkInputs = listOf(stream(id = 9, appName = "Firefox", sinkId = 11))
        }
        val repo = makeRepo(RoutingConfig().withRule("Firefox", AudioChannel.MEDIA))
        val monitor = FakeStreamMonitor(this)
        val engine = RoutingEngine(monitor, fake, repo, this)
        engine.start()
        advanceUntilIdle()
        engine.stop()

        assertTrue(
            fake.movedStreams.any { it.first == 9 && it.second == AudioChannel.MEDIA.sinkName },
            "Expected Firefox to be auto-routed to Media on startup"
        )
    }
}
