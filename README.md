<div align="center">
<img src="https://raw.githubusercontent.com/pimax-apps/pimax-whisperflow/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" />

# WhisperFlow: Neo
**Offline-capable Android speech-to-text with Whisper AI — App · IME Keyboard · Digital Assistant · Galaxy Watch**

[![Build & Test](https://img.shields.io/github/actions/workflow/status/svyatoslavpodolskii/whisperflow-neo/build.yml?branch=voicememory%2Fphase-1-recorder&style=flat-square&label=build)](https://github.com/svyatoslavpodolskii/whisperflow-neo/actions/workflows/build.yml)
[![Download APK](https://img.shields.io/badge/APK-Actions%20artifacts-orange?style=flat-square)](https://github.com/svyatoslavpodolskii/whisperflow-neo/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square)](LICENSE)
[![Min SDK](https://img.shields.io/badge/min%20sdk-26%20(Android%208.0)-brightgreen?style=flat-square)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9.x-purple?style=flat-square)](https://kotlinlang.org)
</div>

---

Личный fork **WhisperFlow: Neo** — захват голоса на Wear OS и голосовая память на Android.
Готовые Phone и Wear APK находятся в **Artifacts** успешных запусков [Build & Test](https://github.com/svyatoslavpodolskii/whisperflow-neo/actions/workflows/build.yml), общий архив — `VoiceMemory-debug`.
Отдельные GitHub Releases пока не публикуются. [Инструкция к тестовой паре](docs/SYNC-TEST-BUILD.md).
Основа проекта: [pimax-apps/pimax-whisperflow](https://github.com/pimax-apps/pimax-whisperflow).

WhisperFlow is a speech-to-text Android app with **dual inference**: remote GPU server (Whisper Large V3 via Gradio) or **fully on-device** TensorFlow Lite (Whisper Tiny/Base/Small). It operates in four modes — standalone app, system keyboard (IME), digital assistant (long-press power), and voice keyboard overlay. A companion **Wear OS** app relays audio from Galaxy Watch for transcription.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          AUDIO PIPELINE                                  │
│                                                                          │
│   🎤 RECORD             ⚙️ PROCESS                📝 OUTPUT             │
│                                                                          │
│   AudioRecorder         ┌─── GPU PATH ──────────────────────────┐       │
│   16kHz mono PCM   ────▶│  WAV file → WhisperApi               │       │
│   WAV encoding          │  POST /gradio_api/upload (multipart)  │──▶ text
│                         │  POST /gradio_api/call/transcribe     │       │
│                         │  GET  /gradio_api/call/transcribe/:id │       │
│                         │  (SSE poll for result)                │       │
│                         └───────────────────────────────────────┘       │
│                                                                          │
│                         ┌─── LOCAL PATH ────────────────────────┐       │
│                    ────▶│  WAV → float samples (PCM 16-bit)     │       │
│                         │  → MelSpectrogram (80×3000 FFT)       │──▶ text
│                         │  → TFLite Interpreter (runSignature)  │       │
│                         │  → WhisperVocab.decode (BPE → text)   │       │
│                         └───────────────────────────────────────┘       │
│                                                                          │
│   ┌──────────────────────────────────────────────────────────────┐      │
│   │                    ENTRY POINTS                               │      │
│   │  MainActivity ─────── Full UI (4 tabs, Compose)               │      │
│   │  WhisperInputService ─ IME keyboard (XML layout)              │      │
│   │  WhisperAssistSession  Overlay (long-press power, View-based) │      │
│   │  WhisperAccessibilityService ─ Hardware PTT (Vol Key)         │      │
│   │  WatchRelayService ─── Background (Galaxy Watch audio relay)  │      │
│   └──────────────────────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 📑 Table of Contents

1. [Four Operating Modes](#-four-operating-modes)
2. [Architecture](#-architecture)
3. [How the Model Works in Memory](#-how-the-model-works-in-memory)
4. [Language Token System](#-language-token-system)
5. [Live Transcription](#-live-transcription)
6. [Model Comparison](#-model-comparison)
7. [Configurable Settings](#-configurable-settings)
8. [Connections](#-connections)
9. [Galaxy Watch Companion](#-galaxy-watch-companion)
10. [Permissions](#-permissions)
11. [Build & Deploy](#-build--deploy)
12. [File Structure](#-file-structure)
13. [Known Issues](#-known-issues)
14. [Troubleshooting](#-troubleshooting)
15. [Version History](#-version-history)

---

## 🎯 Four Operating Modes

### Mode 1: App (Full UI)

| Aspect | Detail |
|--------|--------|
| **Entry** | Launch from home screen |
| **UI** | Jetpack Compose, 4 tabs: Record, History, Connect, Settings |
| **Engine** | `TranscriptionEngine` (persists as long as `MainScreen` composable exists) |
| **Local model** | Stays loaded in RAM across recordings — no reload per transcription |
| **Theme** | Dark noir / electric amber (custom dark color scheme) |

### Mode 2: Assistant (Long-Press Power)

| Aspect | Detail |
|--------|--------|
| **Entry** | Long-press power button → `WhisperAssistSession.onShow()` |
| **UI** | View-based overlay (no Compose) — red/black floating waveform |
| **Engine** | New `LocalWhisperEngine` created per session, closed on hide |
| **Stop modes** | Configurable: SILENCE (auto-stop), MANUAL (power again), PTT (volume release) |
| **Animation** | Configurable: GENIE (Classic Snake) or NATURE (Organic Tree) |
| **Result** | Copied to clipboard, toast shown, overlay dismisses |

### Mode 3: IME (Keyboard)

| Aspect | Detail |
|--------|--------|
| **Entry** | Select "WhisperFlow Voice" as system keyboard |
| **UI** | XML layout — mic button + live preview |
| **Engine** | `LocalWhisperEngine` created on first recording, lives as long as the IME service |
| **Output** | Text committed directly to active text field via `InputConnection.commitText()` |
| **Reliability** | ✅ Only mode with guaranteed text injection into third-party apps |

### Mode 4: Voice Keyboard (Accessibility Overlay) — 🟡 Partial

| Aspect | Detail |
|--------|--------|
| **Entry** | Side key app action, broadcast, volume double-tap, launcher shortcut |
| **UI** | `VoiceKeyboardOverlay` — amber top bar + waveform (`TYPE_ACCESSIBILITY_OVERLAY`) |
| **Output** | ❌ Blocked on WhatsApp and most apps; ✅ works via WhisperFlow IME when active |

---

## 🏗 Architecture

`TranscriptionEngine` is the unified router used in the App (MainActivity). It wraps both `WhisperApi` (GPU) and `LocalWhisperEngine` (TFLite) behind a single `transcribe()` / `transcribeWav()` API.

The **Assistant** and **IME** services manage their own `LocalWhisperEngine` instances directly because they have different lifecycles.

### Inference Routing

```
Settings: inferenceMode
    ├── "GPU_SERVER" → WhisperApi → Gradio HTTP (upload + SSE poll)
    └── "LOCAL"      → LocalWhisperEngine → TFLite (mel → inference → BPE decode)
```

---

## 🧠 How the Model Works in Memory

The TFLite interpreter is **loaded once** and stays resident in RAM. It is NOT reloaded per transcription.

### RAM Footprint by Model

| Model | Disk Size | RAM Required | Notes |
|-------|-----------|-------------|-------|
| **Tiny** | ~42 MB | ~150 MB | Good for English, fastest |
| **Base** | ~78 MB | ~250 MB | Good multilingual balance |
| **Small** | ~249 MB | ~600 MB | Best quality; needs ≥1 GB max heap |

The model file is memory-mapped via `FileChannel.map(READ_ONLY)` — the OS manages paging.

### Thread Safety

TFLite's `Interpreter` is NOT thread-safe. `LocalWhisperEngine` uses a `Mutex` to prevent concurrent access:

```kotlin
private val inferenceMutex = Mutex()

suspend fun transcribe(samples: FloatArray, language: Int?): TranscriptionResult =
    withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            runInference(samples, Task.TRANSCRIBE, language)
        }
    }
```

---

## 🌐 Language Token System

Without forced language tokens, Tiny/Base models frequently misidentify the language. WhisperFlow maps human-readable language names → ISO codes → Whisper BPE token IDs:

```
"English" → "en" → languageTokenFor("en") → 50260
"German"  → "de" → languageTokenFor("de") → 50263
"Auto"    → null → serving_transcribe (auto-detect)
```

**Supported languages (Settings UI exposes 13):** Auto, English, German, Chinese, Spanish, French, Italian, Portuguese, Russian, Japanese, Korean, Arabic, Hindi. Any 2-letter ISO 639-1 code also works directly.

---

## 📡 Live Transcription

While recording, audio is split into overlapping chunks and transcribed in near-real-time.

| Aspect | GPU_SERVER | LOCAL |
|--------|-----------|-------|
| **Concurrency** | Fire-and-forget parallel coroutines | Sequential — await result before next chunk |
| **Why** | GPU handles concurrent requests | TFLite NOT thread-safe; concurrent calls crash |

**Hallucination filter:** amplitude gate (skip silent chunks) + text filter (~60 phantom phrases) + amplitude-aware bypass (if user actually spoke, filter is skipped).

---

## 📊 Model Comparison

Models are downloaded from HuggingFace ([DocWolle/whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models)) and stored in `Android/data/com.pimax.whisperflow/files/whisper_models/`.

| | 🟢 Tiny | 🔵 Base | 🟣 Small |
|---|---------|---------|----------|
| **Disk** | ~42 MB | ~78 MB | ~249 MB |
| **Speed** | 3–5s / 30s audio | 6–10s / 30s audio | 15–25s / 30s audio |
| **Quality** | Good for English | Good multilingual | Great quality |
| **Live chunks** | ✅ Fast | ✅ Usable (2s+ intervals) | ⚠️ Too slow for live |

---

## ⚙️ Configurable Settings

All settings persist via Jetpack DataStore Preferences.

### Core Settings

| Setting | Default | Options |
|---------|---------|---------|
| **Server URL** | *(empty — configure on first launch)* | Any Gradio endpoint |
| **Language** | `Auto` | Auto, English, German, Chinese, Spanish, French, Italian, Portuguese, Russian, Japanese, Korean, Arabic, Hindi |
| **Task** | `transcribe` | `transcribe`, `translate` |
| **Inference Mode** | `GPU_SERVER` | `GPU_SERVER`, `LOCAL` |
| **Local Model** | `TINY` | `TINY`, `BASE`, `SMALL` |

### Transcription Tuning

| Setting | Default | Description |
|---------|---------|-------------|
| **Chunk Interval** | `1800` ms | Time between live chunks |
| **Silence Timeout** | `1800` ms | How long silence before auto-stop (assistant) |
| **Hallucination Threshold** | `0.15` | Peak amplitude below which text filter activates |
| **Live Chunking** | `true` | Toggle real-time transcription |

### Connection Settings

All service URLs are configured in the **Connect** tab. Enter your server's base IP once — the port placeholders for each service auto-fill from that host.

| Service | Default Port | Protocol |
|---------|-------------|----------|
| **Whisper GPU server** | `7862` | Gradio 6.x |
| **Tasks service** | `8058` | REST `POST /api/tasks` |
| **Streak Tracker** | `8055` | REST `POST /api/whisper-hook` |
| **LLM Gateway** | `8068` | REST |

---

## 🔗 Connections

After every successful transcription (App, Assistant, and IME modes):

1. If **Tasks** enabled → `TasksApi.createTask(text)` — `POST {tasksUrl}/api/tasks`
2. If **Streak** enabled → `WhisperHookApi.send(text)` — `POST {streakUrl}/api/whisper-hook`
3. Toast combines results: `"✅ Task • 🔥 Streak completed!"`

Both connections are disabled by default. Configure in the **Connect** tab.

---

## ⌚ Galaxy Watch Companion

A companion Wear OS app (`wear/` module) lets you record audio directly from a Galaxy Watch and receive transcription results.

| Mode | Description |
|------|-------------|
| **Relay** | Watch records audio → sends to phone via Wearable Data Layer → phone transcribes → result shown on watch |
| **Direct GPU** | Watch connects directly to GPU server via OkHttp (bypasses phone) |

**Setup:** Install the wear APK (`./gradlew :wear:assembleDebug`) on your watch and ensure it's paired to the phone running WhisperFlow.

---

## 🔐 Permissions

### Phone App

| Permission | Reason |
|-----------|--------|
| `RECORD_AUDIO` | Core recording functionality |
| `INTERNET` | GPU server + service connections |
| `WAKE_LOCK` | Reliable recording during screen off |
| `FOREGROUND_SERVICE` | Long recordings |
| `POST_NOTIFICATIONS` | Recording status |
| `SYSTEM_ALERT_WINDOW` | Assistant and voice keyboard overlays |
| `WRITE_SECURE_SETTINGS` | Programmatic IME switching (grant via ADB once) |
| `VIBRATE` | Haptic feedback |
| `BIND_INPUT_METHOD` | IME keyboard service |
| `BIND_VOICE_INTERACTION` | Digital assistant service |
| `BIND_ACCESSIBILITY_SERVICE` | Hardware PTT + voice keyboard |

### Wear App

| Permission | Reason |
|-----------|--------|
| `RECORD_AUDIO` | Recording on watch |
| `INTERNET` | Direct GPU mode |
| `WAKE_LOCK` | Recording reliability |

---

## 🔨 Build & Deploy

### Prerequisites

```bash
export ANDROID_HOME=~/Library/Android/sdk
adb devices  # verify connected device
```

### Build

```bash
# Debug build (phone app)
./gradlew :app:assembleDebug

# Wear OS build
./gradlew :wear:assembleDebug

# One-liner: build + install + launch
./run-phone.sh
```

### APK Output

| Variant | Path |
|---------|------|
| Phone Debug | `app/build/outputs/apk/debug/whisperflow-debug.apk` |
| Phone Release | `app/build/outputs/apk/release/whisperflow-release.apk` |
| Wear Debug | `wear/build/outputs/apk/debug/whisperflow-wear-debug.apk` |

### View Logs

```bash
# All WhisperFlow logs
adb logcat | grep -iE "WhisperFlow|WhisperApi|WhisperAssist|AudioRecorder|VoiceKbOverlay"

# Local inference pipeline
adb logcat | grep -E "LocalWhisperEngine|TranscriptionEngine|MelSpectrogram|WhisperVocab"
```

### Samsung-Specific Setup

**Enable IME:** Settings → General Management → Keyboard List → Enable "WhisperFlow Voice"

**Enable Assistant:** Settings → Apps → Default apps → Digital assistant app → WhisperFlow Voice

**Enable Voice Keyboard (Side Key):** Settings → Advanced features → Side key → Double press → App action → WhisperFlow Voice Keyboard

**Enable Accessibility (required for Voice Keyboard + volume PTT):**
Settings → Accessibility → Installed apps → WhisperFlow → ON

**Grant IME switching permission (one-time ADB):**
```bash
adb shell pm grant com.pimax.whisperflow android.permission.WRITE_SECURE_SETTINGS
```

---

## 📁 File Structure

### Phone App (`app/src/main/java/com/pimax/whisperflow/`)

| File | Description |
|------|-------------|
| `MainActivity.kt` | Main Compose UI — 4 tabs (Record, History, Connect, Settings), waveform, live transcription loop |
| `SettingsDataStore.kt` | All persistent settings with Jetpack DataStore Preferences |
| `WhisperApi.kt` | Gradio 6.x HTTP client — upload, call/transcribe, SSE poll, hallucination filter |
| `WhisperAssistService.kt` | Digital assistant service + session — overlay UI, silence detection, wake lock |
| `WhisperInputService.kt` | IME keyboard service — mic button, live preview, text commitment |
| `VoiceKeyboardOverlay.kt` | Voice Keyboard floating overlay (accessibility window) |
| `AudioRecorder.kt` | Core recording engine — 16kHz mono PCM, WAV encoding, chunk extraction |
| `SoundFeedback.kt` | Synthesized audio tones and haptic patterns |
| `TasksApi.kt` | Tasks HTTP client — `POST /api/tasks`, health check |
| `WhisperHookApi.kt` | Generic whisper-hook client — works with any compatible service |
| `WatchRelayService.kt` | Wearable Data Layer listener — receives watch audio, transcribes |

### Local Inference (`app/src/main/java/com/pimax/whisperflow/local/`)

| File | Description |
|------|-------------|
| `LocalWhisperEngine.kt` | TFLite interpreter wrapper — model loading, mutex-guarded inference |
| `TranscriptionEngine.kt` | Unified engine router — switches between GPU_SERVER and LOCAL mode |
| `ModelManager.kt` | Model download/delete — HuggingFace download with progress |
| `WhisperVocab.kt` | Binary vocab loader + BPE tokenizer — decodes token IDs to text |
| `MelSpectrogram.kt` | Audio feature extraction — 80-bin mel spectrogram via FFT |

### Wear OS App (`wear/src/main/java/com/pimax/whisperflow/wear/`)

| File | Description |
|------|-------------|
| `WearMainActivity.kt` | Watch UI — Compose for Wear, record button, result display |
| `WatchDataSender.kt` | Wearable Data Layer client — sends audio as Asset, awaits result |
| `WearWhisperApi.kt` | Direct GPU client for watch |
| `WearSettings.kt` | Watch settings — DataStore for mode, server URL, language |

---

## ⚠️ Known Issues

| Issue | Notes |
|-------|-------|
| **Emulator hallucinations** | Virtual mic outputs silence → Whisper produces phantom text. Test on real device. |
| **No true streaming** | Gradio returns full text on `event: complete`. "Streaming" is simulated via audio chunking. |
| **IME / Assistant can't request permissions** | Grant mic access via the main app before first use. |
| **Voice keyboard injection blocked** | `ACTION_SET_TEXT` + `ACTION_PASTE` fail on WhatsApp and most apps. Use WhisperFlow IME keyboard instead. |
| **Cleartext traffic required** | `android:usesCleartextTraffic="true"` set for HTTP server URLs. |
| **XNNPACK disabled** | `setUseXNNPACK(false)` required — XNNPACK incompatible with Whisper's dynamic tensors. |
| **Small model not practical for live chunks** | Each chunk takes 15s+ on most phones. Use Tiny or Base for live mode. |

---

## 🔧 Troubleshooting

| Problem | Fix |
|---------|-----|
| "GPU OFFLINE" in app | Check server URL in Settings; verify Whisper server is running |
| "⚠ MODEL NOT INSTALLED" | Open app → Settings → download a model |
| IME shows "⚠ Open WhisperFlow app" | Open main app, tap record, grant mic permission |
| Empty transcription | Audio too short or silent; check chunk interval |
| Garbled / wrong language | Set language explicitly — avoid Auto on Tiny/Base |
| Tasks/Streak not sending | Enable in Connect tab; verify service URL |
| Watch says "Phone not reachable" | Open WhisperFlow on phone; check Bluetooth |
| Voice keyboard goes to home screen | Side key → App action → Voice Keyboard (NOT Digital assistant) |
| Voice keyboard transcribes but doesn't type | Use WhisperFlow IME in that app |

---

## 📋 Version History

See [CHANGELOG.md](CHANGELOG.md) for full details.

| Version | Highlights |
|---------|-----------|
| **v2.5** | Voice Keyboard overlay mode (partial — inject blocked on most apps) |
| **v2.4** | Independent sound & haptic settings, vibration intensity, Samsung haptic fix |
| **v2.3** | Hardware PTT via volume keys, Nature animation mode, global audio focus |
| **v2.1** | On-device TFLite inference, Galaxy Watch companion, Connections system |
| **v1.0** | GPU server transcription, live chunking, IME, Assistant, hallucination filtering |

---

## Star History

[![GitHub Stars](https://img.shields.io/github/stars/pimax-apps/pimax-whisperflow?style=social)](https://github.com/pimax-apps/pimax-whisperflow/stargazers)
