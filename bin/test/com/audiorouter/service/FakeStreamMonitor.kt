package com.audiorouter.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Test double for StreamMonitor with injectable events. */
class FakeStreamMonitor(scope: CoroutineScope) : StreamMonitor(FakeAudioService(), scope) {

    private val _fakeEvents = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AudioEvent> = _fakeEvents.asSharedFlow()

    suspend fun emit(event: AudioEvent) = _fakeEvents.emit(event)

    override fun start() { /* no-op: test controls events directly */ }
    override fun stop() {}
}
