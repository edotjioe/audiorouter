package com.audiorouter.service

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

object AudioServiceFactory {
    fun create(): AudioService {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("linux") -> {
                log.info { "Platform: Linux — using PipeWire/PulseAudio backend (pactl)" }
                LinuxAudioService()
            }
            os.contains("windows") -> {
                log.info { "Platform: Windows — using WASAPI backend" }
                WindowsAudioService()
            }
            else -> {
                log.warn { "Unsupported OS '$os' — falling back to Linux backend" }
                LinuxAudioService()
            }
        }
    }
}
