package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream

/** Configurable test double for AudioService. All operations succeed by default. */
class FakeAudioService : AudioService {

    val loadedNullSinks = mutableListOf<AudioChannel>()
    val loadedLoopbacks = mutableListOf<Pair<AudioChannel, String>>()
    val unloadedModules = mutableListOf<Int>()
    val movedStreams = mutableListOf<Pair<Int, String>>()
    val volumeChanges = mutableListOf<Pair<String, Int>>()
    val muteChanges = mutableListOf<Pair<String, Boolean>>()

    var nextModuleId = 100
    var sinkInputs: List<AudioStream> = emptyList()
    var allSinks: List<Pair<Int, String>> = emptyList()
    var shortModules: List<String> = emptyList()

    override suspend fun listRealSinks(): List<Pair<Int, String>> =
        allSinks.filter { (_, name) -> !name.startsWith("AudioRouter_") }

    override suspend fun listAllSinks(): List<Pair<Int, String>> = allSinks

    override suspend fun loadNullSink(channel: AudioChannel): Int {
        loadedNullSinks += channel
        return nextModuleId++
    }

    override suspend fun loadLoopback(channel: AudioChannel, outputSinkName: String): Int {
        loadedLoopbacks += channel to outputSinkName
        return nextModuleId++
    }

    override suspend fun unloadModule(id: Int): Boolean {
        unloadedModules += id
        return true
    }

    override suspend fun listShortModules(): List<String> = shortModules

    override suspend fun listSinkInputs(): List<AudioStream> = sinkInputs

    override suspend fun moveSinkInput(sinkInputId: Int, sinkName: String): Boolean {
        movedStreams += sinkInputId to sinkName
        return true
    }

    override suspend fun setSinkVolume(sinkName: String, percent: Int): Boolean {
        volumeChanges += sinkName to percent
        return true
    }

    override suspend fun setSinkMute(sinkName: String, muted: Boolean): Boolean {
        muteChanges += sinkName to muted
        return true
    }

    override fun startSubscribeProcess(): Process =
        ProcessBuilder("true").start()

    override fun openLevelCapture(channel: AudioChannel): Process? = null
}
