# Contributing to WhisperFlow

Thanks for your interest in contributing!

## Getting Started

```bash
git clone https://github.com/pimax-apps/pimax-whisperflow.git
cd pimax-whisperflow
./gradlew :app:testDebugUnitTest
```

## Development Guidelines

- **No hardcoded IPs.** All server URLs are runtime-configurable via `SettingsDataStore`. Never hardcode an IP address or hostname.
- **Run tests before submitting.** `./gradlew :app:testDebugUnitTest` must pass.
- **Build from `run-phone.sh`** for device deployment — it handles build, install, and launch.

## Pull Request Process

1. Fork the repo and create a feature branch from `main`.
2. Make your changes with clear, focused commits.
3. Run `./gradlew :app:testDebugUnitTest` — ensure all tests pass.
4. Open a PR using the provided template.

## Reporting Bugs

Use the **Bug Report** issue template. Include:
- App version
- Android version and device model
- Inference mode (GPU / Local Tiny/Base/Small)
- Relevant logcat output: `adb logcat | grep -iE "WhisperFlow|WhisperApi|AudioRecorder"`

## Code Style

- Kotlin idiomatic style — follow existing patterns in the codebase.
- Compose UI: match the existing dark noir / electric amber theme tokens.
- Emoji log prefixes: `✅ ⚠️ ❌ 📤 📥 🔍` — keeps logcat scannable.
