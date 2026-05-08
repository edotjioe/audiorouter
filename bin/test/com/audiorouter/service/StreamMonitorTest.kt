package com.audiorouter.service

import kotlin.test.*

class StreamMonitorTest {

    private val monitor = StreamMonitor(
        pipeWire = FakeAudioService(),
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
    )

    // ── parseEvent ────────────────────────────────────────────────────────

    @Test
    fun `parseEvent returns SinkInputAdded for new sink-input line`() {
        val event = monitor.parseEvent("Event 'new' on sink-input #42")
        assertEquals(AudioEvent.SinkInputAdded(42), event)
    }

    @Test
    fun `parseEvent returns SinkInputRemoved for remove sink-input line`() {
        val event = monitor.parseEvent("Event 'remove' on sink-input #7")
        assertEquals(AudioEvent.SinkInputRemoved(7), event)
    }

    @Test
    fun `parseEvent returns SinkChanged for sink change line`() {
        val event = monitor.parseEvent("Event 'change' on sink #1")
        assertEquals(AudioEvent.SinkChanged, event)
    }

    @Test
    fun `parseEvent returns null for unrelated lines`() {
        assertNull(monitor.parseEvent("Event 'new' on client #5"))
        assertNull(monitor.parseEvent("Event 'change' on server"))
        assertNull(monitor.parseEvent(""))
    }

    @Test
    fun `parseEvent returns null when sink-input id is missing`() {
        assertNull(monitor.parseEvent("Event 'new' on sink-input (no id)"))
    }

    @Test
    fun `parseEvent handles large sink-input ids`() {
        val event = monitor.parseEvent("Event 'new' on sink-input #99999")
        assertEquals(AudioEvent.SinkInputAdded(99999), event)
    }

    @Test
    fun `parseEvent returns SinkInputRemoved with correct id`() {
        val event = monitor.parseEvent("Event 'remove' on sink-input #1234")
        assertEquals(AudioEvent.SinkInputRemoved(1234), event)
    }
}
