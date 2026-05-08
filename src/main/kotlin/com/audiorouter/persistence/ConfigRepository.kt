package com.audiorouter.persistence

import com.audiorouter.model.RoutingConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

private val log = KotlinLogging.logger {}

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@OptIn(FlowPreview::class)
class ConfigRepository(private val scope: CoroutineScope) {

    private val configDir: Path = run {
        val xdgConfig = System.getenv("XDG_CONFIG_HOME")
        val base = if (xdgConfig.isNullOrBlank()) Path(System.getProperty("user.home")).resolve(".config")
                   else Path(xdgConfig)
        base.resolve("AudioRouter")
    }

    private val configFile: Path = configDir.resolve("config.json")
    private val tmpFile: Path = configDir.resolve("config.json.tmp")

    private val _config = MutableStateFlow(RoutingConfig())
    val config: StateFlow<RoutingConfig> = _config.asStateFlow()

    private val pendingWrite = MutableSharedFlow<RoutingConfig>(extraBufferCapacity = 1)
    private var writeJob: Job? = null

    fun load() {
        configDir.createDirectories()
        if (configFile.exists()) {
            runCatching {
                _config.value = json.decodeFromString(configFile.readText())
                log.info { "Loaded config from $configFile" }
            }.onFailure {
                log.warn { "Failed to read config: ${it.message} — using defaults" }
            }
        } else {
            log.info { "No config found at $configFile — using defaults" }
        }

        writeJob = scope.launch {
            pendingWrite
                .debounce(300)
                .collect { cfg ->
                    writeAtomic(cfg)
                }
        }
    }

    fun update(transform: (RoutingConfig) -> RoutingConfig) {
        val updated = transform(_config.value)
        _config.value = updated
        pendingWrite.tryEmit(updated)
    }

    suspend fun flushImmediately() {
        writeJob?.cancel()
        writeAtomic(_config.value)
    }

    private fun writeAtomic(cfg: RoutingConfig) {
        runCatching {
            val text = json.encodeToString(RoutingConfig.serializer(), cfg)
            tmpFile.writeText(text)
            Files.move(tmpFile, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            log.debug { "Config saved to $configFile" }
        }.onFailure {
            log.error { "Failed to write config: ${it.message}" }
        }
    }
}
