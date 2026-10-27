# WhisperFlow → Voice Memory: аудит и первый этап

Этот документ фиксирует первоначальный аудит и локальный этап. Текущая синхронизация,
дисковый журнал и пара APK описаны в [SYNC-TEST-BUILD.md](SYNC-TEST-BUILD.md).

Аудит исходного commit `28f3db5a9b193587ea537b96cb9f392e0ab0f001`.
Дата: 27 октября 2026. Android-сборки и unit tests прошли в CI; проверка на Galaxy Watch 6 40 mm ещё требуется.

## 1. Phone — FACT

`MainActivity.kt` содержит Compose UI, запись, запуск транскрипции, waveform и историю.
`AudioRecorder.kt` записывает PCM/WAV. `SettingsDataStore.kt` хранит настройки и JSON-историю,
умеет копировать аудио в постоянное хранилище. Общего Room inbox и persistent transcription worker нет.
UI запускает транскрипцию через coroutine scope; существование результата в истории не является
гарантией сохранности входящего аудио при ошибке inference.

## 2. Wear — FACT

`wear/src/main/AndroidManifest.xml` объявляет launcher `.WearMainActivity`, разрешения
`RECORD_AUDIO`, `VIBRATE`, `WAKE_LOCK` и Internet. Namespace и исходный applicationId:
`com.pimax.whisperflow.wear`. minSdk 30, target/compileSdk 34.
В `WearMainActivity.kt` текущей версии нет routes, вкладок `record / history` или History UI:
она непосредственно показывает amber/noir кнопку микрофона и waveform.
`WearAudioRecorder.kt` использует AudioRecord: 16 kHz, mono, PCM16; исходно буфер только в RAM,
автостоп после 1,8 секунды тишины и предел 15 секунд. Ошибки старта логировались без уведомления UI.

## 3. Watch → Phone — FACT

Исходный `finishRecording` получает WAV в RAM → `WatchDataSender.sendAudioAndAwaitResult`
→ Asset в DataItem `/whisper/audio` → `WatchRelayService` → cache WAV → GPU → результат MessageClient.
Только успешная транскрипция приводила к постоянному сохранению аудио и JSON-истории;
cache удалялся в finally и при ошибках. UUID, дедупликации, durable ACK, outbox и фоновой очереди нет.
Один фиксированный DataItem path допускает перезапись предыдущей записи.
Получение `putDataItem` или текста не подтверждает durable phone persistence.

У Phone applicationId `com.pimax.whisperflow`, у исходного Wear — `com.pimax.whisperflow.wear`.
Data Layer требует одинаковых package name и подписей:
[Android Developers](https://developer.android.com/training/wearables/data/overview#security).
Это отдельный подтверждённый дефект конфигурации будущей синхронизации, а не доказанная причина
пустого экрана. В Phase 1 идентификатор ещё не менялся: перед Phase 4 нужно согласовать identity
и установить совместимую пару APK; миграция не должна удалять старые pending files.

## 4. Транскрипция — FACT

`local/TranscriptionEngine.kt` маршрутизирует LOCAL, GPU_SERVER и LLM_GATEWAY.
`LocalWhisperEngine`, `MelSpectrogram`, `WhisperVocab` и `ModelManager` реализуют TFLite Whisper
и выбор Tiny/Base/Small. `WhisperApi` работает через Gradio upload/call/SSE.
Исходный default — GPU_SERVER. WatchRelay использует WhisperApi напрямую, обходя local engine.
Для Phase 7/8 нужно сохранить engines и вынести длительную обработку из receiver в persistent worker.

## 5. Assistant — FACT

Phone `WhisperAssistService.kt` содержит VoiceInteractionService и сессии overlay для системного
Assistant (long press Power). Это отдельный lifecycle записи/inference; он сохранён без изменений.
Наличие Phone Assistant не подтверждает работу ROLE_ASSISTANT или long press Home на Wear.
Поддержка One UI Watch — REQUIRES DEVICE TEST в Phase 12.

## 6. Внешние подключения — FACT

GPU Server, Tasks, Streak Tracker, LLM Gateway, связанные auth/assistant endpoints и встроенный
HTTP server существуют в исходнике. Их реализации сохранены. Перенос UI в «Дополнительно»,
LOCAL default и упрощение настроек относятся к последующим этапам.

## 7. Что сохраняем

Phone recorder/UI, waveform, engines, model management, API, assistant и integrations не переписаны.
Wear waveform и amber/noir tokens сохранены. WAV encoding выделен из существующего recorder
в Android-free `WearWav.kt` для проверки формата; алгоритм encoding сохранён.

## 8. Экран `record / history`

- FACT: такого экрана нет в проверенном WearMainActivity исходного commit.
- LIKELY: другой APK, build variant, версия или launcher. Это гипотеза, не диагноз.
- REQUIRES DEVICE TEST: package/version/launcher установленного APK, microphone appops,
  реальный UI dump, screenshot и exception stack traces до любых выводов.

Для сбора данных при открытом проблемном экране:

```powershell
powershell -File tools/watch-diagnostics.ps1 -Serial WATCH_SERIAL -Adb PATH_TO_ADB
```

Скрипт не устанавливает APK, не очищает logcat и не удаляет пользовательские данные.
Результат — `logs/watch-diagnostics/` (исключён из Git). Он собирает обе известные package identity.
Нужно также получить `pm path PACKAGE`, pull установленного APK и сравнить SHA256,
versionCode и сертификат с APK соответствующего commit. Название файла не доказывает его происхождение.

## 9. Изменения Phase 1

- `WearMainActivity.kt`: обычный запуск сразу на Record; разрешение запускает запись после grant;
  телефон/Internet не нужны; timer; доступные подписи кнопок; локальное сохранение перед «Сохранено»;
  короткая вибрация; возврат в idle. Убраны polling каждые пять секунд и live GPU uploads.
- `WearAudioRecorder.kt`: autoStopOnSilence=false; предел увеличен до десяти минут;
  monotonic clock; read chunks по 50 ms; stop ждёт завершения записи PCM перед WAV encoding;
  ошибки AudioRecord передаются в UI; короткие записи не отбрасываются.
- `PendingRecordingStore.kt`: WAV и metadata в `filesDir/pending_recordings/`, UUID,
  AtomicFile + fsync, `LOCAL`. Успех показывается только после сохранения обоих файлов.
- `WearWav.kt`, `WearWavTest.kt`: проверяемый PCM16/WAV encoder и тесты заголовка/длины/payload.
- `res/values` и `values-en`: русские default строки активного Record flow и английская локализация.
- `.github/workflows/build.yml`: обе сборки, оба набора unit tests и три именованных artifacts.

Это локальный recorder этапа 1, не завершённая persistent sync queue. Legacy sender/receiver и GPU API
остались в проекте, но новая запись из UI пока не отправляется: нельзя считать старый результат ACK.
WAV остаётся на часах, автоматического удаления нет.

Ограничения: metadata ещё не читается как UI-очередь; нет recovery orphan WAV, retry scheduler,
durable phone ACK и дедупликации. Текущая активная запись всё ещё в RAM до Stop; закрытие Activity,
process death или сбой записи могут потерять ещё не сохранённое аудио. При ошибке сохранения
PCM-буфер удерживается до повторного нажатия; дискового recovery ещё нет. Это блокирует production acceptance и должно быть
исправлено перед использованием для важных записей. Foreground recording lifecycle и disk spool
требуют отдельной реализации с сохранением текущего AudioRecord pipeline.

## 10. GitHub / CI / Releases

Создан настоящий fork: https://github.com/svyatoslavpodolskii/pimax-whisperflow

- origin: fork; upstream: https://github.com/pimax-apps/pimax-whisperflow
- рабочая ветка: `voicememory/phase-1-recorder`; upstream не merged.
- исходный build workflow собирал assembleDebug, но публиковал только Phone debug artifact;
  теперь явно собираются `:app:assembleDebug :wear:assembleDebug` из одного checkout.
- artifacts: `VoiceMemory-Phone-debug`, `VoiceMemory-Wear-debug`, `VoiceMemory-debug`.
- исходный release workflow выпускает только Phone debug APK. Production signing через Secrets
  ещё не внедрён; release pipeline расширяется после стабилизации.
- [Upstream releases](https://github.com/pimax-apps/pimax-whisperflow/releases): несколько тегов
  указывают на один commit 28f3db5; название release не определяет содержимое Wear APK.
- [Actions run 40506486886](https://github.com/svyatoslavpodolskii/pimax-whisperflow/actions/runs/40506486886)
  для commit `6e8ed9f578efe1d416da562e239684a958d2b538` завершился успешно: unit tests обоих модулей,
  обе debug сборки и публикация всех трёх artifacts. Оба APK относятся к одному commit.

## 11. Проверка / следующий шаг

Выполнен JVM smoke test реального `WearWav.encode` для 2/8/32000/320000 PCM bytes:
RIFF/WAVE, длины chunks, sample rate, byte rate, channels, bits, block alignment и payload — PASS.
Это не доказывает работу AudioRecord, UI или Galaxy Watch.

Полная команда проверки:

Локальный запуск завершился до компиляции: `SDK location not found`.
Android SDK не обнаружен в стандартных путях и не задан через ANDROID_HOME/local.properties.
Android unit tests и сборка APK локально не подтверждены. Отдельный JVM smoke test выше выполнен.
JUnit tests и сборки APK успешно выполнены в GitHub Actions (ссылка выше).

```powershell
.\gradlew.bat :app:assembleDebug :wear:assembleDebug :app:testDebugUnitTest :wear:testDebugUnitTest
```

Device acceptance: 40 mm круглый экран; обычный запуск; permission grant/deny/retry;
record/таймер/waveform/Stop; silent pauses; короткая и длинная диктовка; отсутствие телефона и
Internet; проверка сохранённого WAV через playback; повторный запуск с сохранностью files.
Отдельно проверить Home/back/экран выключен и убийство процесса — текущий lifecycle ещё ограничен.

Далее: device diagnosis → disk-backed recording lifecycle → persistent outbox/recovery (Phase 3)
→ Room phone inbox/validation/dedup → durable ACK → event-driven sync → единый WorkManager pipeline.
