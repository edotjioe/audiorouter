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

/**
 * Reads and writes [RoutingConfig] to disk, with debounced atomic saves.
 *
 * The config file lives at `~/.config/AudioRouter/config.json` by default.
 * All writes are performed atomically via a temp-file rename so a crash during
 * a save never leaves a corrupt config behind.
 *
 * Usage:
 * 1. Call [load] once at startup to read the persisted config and start the write job.
 * 2. Read current state from [config] (a [StateFlow]).
 * 3. Apply mutations via [update] — in-memory state changes immediately and a debounced
 *    disk write is queued automatically.
 * 4. Call [flushImmediately] on shutdown to bypass the debounce and write synchronously.
 *
 * @param scope             Coroutine scope that owns the background write job.
 * @param configDirOverride Override the config directory (used in tests to isolate state).
 */
@OptIn(FlowPreview::class)
class ConfigRepository(
    private val scope: CoroutineScope,
    configDirOverride: Path? = null
) {
    private val configDir: Path = configDirOverride ?: run {
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

    /** Loads config from disk (creates the config dir if needed) and starts the write-debounce job. */
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

    /**
     * Applies [transform] to the current config and enqueues a debounced disk write.
     * The [config] StateFlow is updated synchronously before this function returns.
     */
    fun update(transform: (RoutingConfig) -> RoutingConfig) {
        val updated = transform(_config.value)
        _config.value = updated
        pendingWrite.tryEmit(updated)
    }

    /** Cancels the debounce job and writes the current config to disk immediately. Call on shutdown. */
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
