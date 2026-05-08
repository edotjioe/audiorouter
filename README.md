# AudioRouter

A Linux desktop application that replicates the SteelSeries GG Sonar feature — virtual per-category audio channels with independent volume control, per-application routing, and real-time VU meters.

Built with **Kotlin + Compose Multiplatform Desktop** on top of **PipeWire** (`pactl`).

---

## Features

- **6 virtual audio channels** — Master, Game, Chat, Media, Aux, Mic
- **Per-application routing** — assign any audio stream to a channel; rules persist across app restarts
- **Independent volume & mute** per channel
- **Per-channel output selection** — route different channels to different audio devices (e.g. headset for Chat, speakers for Media)
- **Real-time stereo VU meters** — driven by actual PipeWire monitor sources, not simulated
- **Drag-and-drop assignment** — drag a stream from the unassigned panel onto a channel row; tap for a menu fallback
- **System tray** — hide to tray, restore with a click (uses StatusNotifierItem for KDE Plasma 6 / Wayland)
- **Live CPU & sample rate display** in the header
- **Config persistence** — volumes, mutes, routing rules and output choices saved to `~/.config/AudioRouter/config.json`

---

## Requirements

| Dependency | Version |
|---|---|
| Linux (PipeWire) | PipeWire ≥ 0.3 with PulseAudio compat layer |
| `pactl` | ≥ 17.0 |
| JDK | 21+ (tested with Amazon Corretto 24) |
| Gradle | 8.14+ (wrapper included) |

Tested on **Bazzite Linux** (KDE Plasma 6, Wayland).

---

## Building & Running

```bash
# Run directly from source
./gradlew run

# Package as RPM / DEB / AppImage
./gradlew packageRpm
./gradlew packageDeb
./gradlew packageAppImage
```

> **Note:** Run from a terminal that has `DISPLAY` or `WAYLAND_DISPLAY` set. The Gradle `run` task forwards these automatically.

---

## How It Works

On startup AudioRouter:

1. Loads config from `~/.config/AudioRouter/config.json`
2. Scans for and removes any orphaned `AudioRouter_*` PipeWire modules from a previous session
3. Creates one **null-sink** + **loopback** module pair per routing channel (Game → Chat → Media → Aux → Mic)
4. Applies stored volumes via `pactl set-sink-volume`
5. Subscribes to `pactl subscribe` for real-time stream events
6. Re-applies saved routing rules to any already-running audio streams
7. Starts per-channel `pacat` processes to feed the live VU meters

On shutdown (window close, tray quit, or SIGTERM) all virtual modules are unloaded cleanly.

---

## Architecture

```
PipeWireService        pactl / pw-cli shell wrapper
VirtualSinkManager     null-sink + loopback lifecycle per channel
StreamMonitor          pactl subscribe → SharedFlow of AudioEvents
RoutingEngine          consumes events, applies AppRules, moves streams
VolumeController       debounced volume/mute per channel sink
LevelMonitor           pacat per-channel RMS → StateFlow<Pair<Float,Float>>
ConfigRepository       atomic JSON read/write, debounced saves

UI (Compose Multiplatform Desktop)
  MainWindowStack      root layout — channel rows + unassigned drawer
  ChannelRow           VU meter · volume slider · output picker · mute
  VuMeter              real-level stereo bar with peak hold
  DragController       pointer-tracked drag state for stream assignment
```

---

## Config File

Located at `~/.config/AudioRouter/config.json`. Edited automatically — no need to touch it manually.

```json
{
  "version": 1,
  "outputSinkName": "alsa_output.pci-0000_00_1f.3.analog-stereo",
  "channelOutputSinks": {
    "CHAT": "alsa_output.usb-headset.analog-stereo"
  },
  "channelVolumes": { "MASTER": 100, "GAME": 85, "CHAT": 90, "MEDIA": 75, "AUX": 100, "MIC": 80 },
  "channelMutes":   { "MASTER": false, "GAME": false, "CHAT": false, "MEDIA": false, "AUX": false, "MIC": false },
  "appRules": [
    { "appName": "Firefox",  "channel": "MEDIA" },
    { "appName": "Discord",  "channel": "CHAT"  },
    { "appName": "Steam",    "channel": "GAME"  }
  ]
}
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Compose Multiplatform Desktop 1.7.3 (Material 3) |
| Audio backend | PipeWire via `pactl` / `pacat` shell commands |
| Async | Kotlin Coroutines 1.9.0 |
| Serialization | kotlinx.serialization 1.7.3 |
| System tray | dorkbox/SystemTray 4.4 (StatusNotifierItem / KDE Plasma 6) |
| Logging | kotlin-logging + Logback |
| Build | Gradle 8.14 (Kotlin DSL) |
