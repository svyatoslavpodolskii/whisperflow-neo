# Changelog

All notable changes to WhisperFlow are documented here.

## [2.5] — Voice Keyboard Mode (Partial)

- **Voice Keyboard overlay** — `TYPE_ACCESSIBILITY_OVERLAY` top bar; does not use assistant session, avoids home-screen takeover
- **FocusTextInjector** — cached field node, gesture tap, `SET_TEXT` + paste fallbacks for accessibility text injection
- **WhisperInputBridge** — `InputConnection` path when WhisperFlow IME is active keyboard (guaranteed injection)
- **Assistant suppression** — `showSession()` blocked 2.5 s after voice keyboard trigger to prevent double-firing
- **Rambler** — voice-edit phrase cleanup via LLM Gateway (assistant + overlay)
- **Known limitation** — accessibility inject blocked on WhatsApp and most third-party apps; IME mode is the reliable workaround

## [2.4] — Sound & Haptic Refactoring

- **Independent Audio & Haptics** — separated Sound and Vibration feedback into independent toggles (removed mutual-exclusion lock)
- **Vibration Intensity Control** — 10%–100% haptic amplitude slider
- **Samsung Haptic Bypass** — resolved Samsung hardware override by substituting `createOneShot` with equivalent `createWaveform` patterns
- **DND/Mute Override** — coupled haptic effects to `AudioAttributes.USAGE_ALARM` so feedback works even with notifications muted

## [2.3] — Hardware PTT & Nature Animation

- **Configurable PTT** — true hardware Push-to-Talk via `WhisperAccessibilityService`; select Volume Up or Volume Down
- **Nature Animation Mode** — new "Tree of Life" amber branch animation growing from the power button lamp
- **Animation Speed Control** — 0.5x–3.0x speed multiplier
- **Global Audio Focus** — centralized `AudioManager` logic in `AudioRecorder` pauses/resumes background media automatically
- **Live Chunking Toggle** — persisted setting to disable real-time intermediate results

## [2.1] — On-Device Inference & Connections

- **On-Device Inference** — TFLite Whisper Tiny/Base/Small with HuggingFace download
- **Forced Language Tokens** — eliminates auto-detect failures on small models
- **Dual Live Chunking** — fire-and-forget for GPU, sequential for LOCAL (TFLite thread safety)
- **Connections System** — Tasks + Streak Tracker integrations via whisper-hook protocol
- **Assistant Stop Modes** — configurable SILENCE (auto) or MANUAL (power button)
- **Configurable Tuning** — chunk interval, silence timeout, hallucination threshold, toast template
- **GPU Activity Monitor** — real-time inference activity graph in Record tab
- **Model Management UI** — download/delete/select models in Settings
- **Galaxy Watch Companion** — Wear OS app with relay and direct GPU modes
- **TranscriptionEngine** — unified inference router with mode switching and activity tracking
- **Amplitude-Aware Hallucination Filter** — bypass filter when user actually spoke
- **Background Transcription** — assistant commits transcription even when dismissed mid-recording
- **Audio History** — last 50 entries with linked WAV files, playback, individual delete
- **Sound Feedback** — synthesized tones with cosine-interpolated envelopes + haptic patterns

## [1.0] — Initial Release

- GPU server transcription via Gradio API
- Live chunking with typewriter animation
- IME keyboard mode
- Assistant mode (long-press power)
- 13-language support
- Hallucination filtering
- WAV recording with history
