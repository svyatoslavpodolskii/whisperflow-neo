package com.pimax.whisperflow

import android.content.Context
import android.graphics.PixelFormat
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private const val TAG = "⌨️ VoiceKbOverlay"
private const val CHUNK_INTERVAL_MS = 1500L
private const val CHUNK_INITIAL_DELAY_MS = 1000L

/**
 * Lightweight floating overlay via accessibility service — stays on top of the
 * current app without triggering digital-assistant home-screen behavior.
 */
class VoiceKeyboardOverlay(private val service: WhisperAccessibilityService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = SettingsDataStore(service.applicationContext)

    private var rootView: View? = null
    private var headerView: TextView? = null
    private var liveTextView: TextView? = null
    private var durationView: TextView? = null
    private var waveformView: AssistWaveformView? = null

    private var audioRecorder: AudioRecorder? = null
    private var isRecording = false
    private var isVisible = false
    private var liveChunks = mutableListOf<String>()
    private var chunkJob: Job? = null
    private var durationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var imeSwitched = false

    private var inferenceMode = "GPU_SERVER"
    private var gatewayUrl = ""
    private var serverUrl = ""
    private var language = "en"
    private var task = "transcribe"
    private var liveChunkingEnabled = true
    private var ramblerEnabled = false
    private var chunkIntervalMs = CHUNK_INTERVAL_MS

    fun isShowing(): Boolean = isVisible

    fun stopIfRecording() {
        if (isVisible && isRecording) stopRecording(commitFinal = true)
    }

    fun show() {
        if (isVisible) return

        if (!FocusTextInjector.beginSession()) {
            Toast.makeText(service, "⚠ Tap a text field first", Toast.LENGTH_SHORT).show()
            return
        }

        SoundFeedback.initialize(service.applicationContext)
        audioRecorder = AudioRecorder(service.applicationContext)
        liveChunks.clear()
        buildAndAttachWindow()
        isVisible = true

        // Auto-switch IME so WhisperInputBridge works (bypasses SET_TEXT blockade)
        imeSwitched = ImeSwitch.switchToWhisperFlow(service.applicationContext)
        if (imeSwitched) {
            Log.d(TAG, "Auto-switched to WhisperFlow IME for reliable injection")
        } else {
            Log.w(TAG, "IME switch failed — WRITE_SECURE_SETTINGS not granted? Falling back to accessibility inject")
        }

        scope.launch {
            inferenceMode = settings.inferenceMode.first()
            gatewayUrl = settings.gatewayUrl.first()
            serverUrl = settings.serverUrl.first()
            language = settings.language.first()
            task = settings.task.first()
            liveChunkingEnabled = settings.liveChunkingEnabled.first()
            ramblerEnabled = settings.ramblerEnabled.first()
            chunkIntervalMs = settings.chunkIntervalMs.first()
            launch { settings.soundEnabled.collect { SoundFeedback.soundEnabled = it } }
            launch { settings.vibrationEnabled.collect { SoundFeedback.vibrationEnabled = it } }
            launch { settings.vibrationIntensity.collect { SoundFeedback.vibrationIntensity = it } }
            // Small delay to let IME service bind before first chunk
            if (imeSwitched) delay(400)
            startRecording()
        }

        Log.d(TAG, "Overlay shown — app stays underneath (imeSwitched=$imeSwitched)")
    }

    fun dismiss() {
        if (!isVisible) return
        chunkJob?.cancel()
        durationJob?.cancel()
        audioRecorder?.release()
        audioRecorder = null
        releaseWakeLock()

        rootView?.let { windowManager.removeView(it) }
        rootView = null
        headerView = null
        liveTextView = null
        durationView = null
        waveformView = null
        isVisible = false
        isRecording = false
        FocusTextInjector.endSession()

        // Restore previous IME after a short delay (let final inject complete)
        if (imeSwitched) {
            scope.launch {
                delay(300)
                ImeSwitch.restorePreviousIme(service.applicationContext)
            }
            imeSwitched = false
        }

        Log.d(TAG, "Overlay dismissed")
    }

    fun destroy() {
        dismiss()
        scope.cancel()
    }

    private fun buildAndAttachWindow() {
        val ctx = service.applicationContext
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF00D0D18.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xCCFF8B00.toInt())
            }
            setOnClickListener {
                if (isRecording) stopRecording(commitFinal = true)
            }
        }

        headerView = TextView(ctx).apply {
            text = "⌨  VOICE KEYBOARD — tap to stop"
            setTextColor(0xFFFF8B00.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(headerView)

        waveformView = AssistWaveformView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        }
        root.addView(waveformView)

        liveTextView = TextView(ctx).apply {
            setTextColor(0xCCFFE0B2.toInt())
            textSize = 13f
            maxLines = 3
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(liveTextView)

        durationView = TextView(ctx).apply {
            setTextColor(0x88FF8B00.toInt())
            textSize = 11f
            gravity = Gravity.END
        }
        root.addView(durationView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(24)
            x = 0
        }

        windowManager.addView(root, params)
        rootView = root
    }

    private fun startRecording() {
        val recorder = audioRecorder ?: return
        if (isRecording) return
        if (!recorder.hasPermission()) {
            Toast.makeText(service, "⚠ Open WhisperFlow app to grant mic permission", Toast.LENGTH_LONG).show()
            dismiss()
            return
        }

        liveChunks.clear()
        isRecording = true
        acquireWakeLock()
        SoundFeedback.playStart()

        if (!recorder.start()) {
            isRecording = false
            releaseWakeLock()
            Toast.makeText(service, "⚠ Mic failed to start", Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }

        waveformView?.startRecordingAnimation(recorder)
        if (liveChunkingEnabled) startLiveChunking()
        startDurationUpdates()
        Log.d(TAG, "Recording started (mode=$inferenceMode)")
    }

    private fun stopRecording(commitFinal: Boolean) {
        if (!isRecording) return
        isRecording = false
        SoundFeedback.playStop()
        chunkJob?.cancel()
        durationJob?.cancel()
        waveformView?.stopAnimation()

        val recorder = audioRecorder ?: return
        val wavFile = recorder.stop()
        releaseWakeLock()

        if (!commitFinal || wavFile == null) {
            dismiss()
            return
        }

        val liveText = liveChunks.joinToString(" ").trim()
        if (liveText.isNotBlank()) {
            scope.launch { finishWithText(liveText, recorder.durationMs.value, wavFile) }
            return
        }

        headerView?.text = "⌨  Processing..."
        scope.launch(Dispatchers.IO) {
            try {
                val text = transcribeFull(wavFile)
                val cleaned = maybeRambler(text)
                withContext(Dispatchers.Main) {
                    if (cleaned.isNotBlank()) {
                        finishWithText(cleaned, recorder.durationMs.value, wavFile)
                    } else {
                        Toast.makeText(service, "⚠ No speech detected", Toast.LENGTH_SHORT).show()
                        dismiss()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(service, "⚠ ${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            }
        }
    }

    private suspend fun finishWithText(text: String, durationMs: Long, wavFile: java.io.File) {
        val injected = FocusTextInjector.updateDictationAsync(text)
        settings.updateLastTranscription(text)
        val audioPath = settings.saveRecordingPermanently(wavFile)
        val modeStr = when (inferenceMode) {
            "LOCAL" -> "LOCAL"
            "LLM_GATEWAY" -> "GATEWAY"
            else -> "GPU_SERVER"
        }
        settings.addToHistory(text, durationMs, language, audioPath, modeStr, sentToTasks = false)
        val msg = when {
            injected -> "✅ Typed: ${text.take(60)}"
            WhisperInputBridge.activeIme == null ->
                "⚠ Enable WhisperFlow keyboard for this app, or tap field again"
            else -> "⚠ Could not type into field"
        }
        Toast.makeText(service, msg, Toast.LENGTH_LONG).show()
        SoundFeedback.playSuccess()
        dismiss()
    }

    private fun startLiveChunking() {
        chunkJob = scope.launch {
            delay(CHUNK_INITIAL_DELAY_MS)
            var chunkIndex = 0
            while (isActive && isRecording) {
                val chunkResult = audioRecorder?.saveChunk()
                if (chunkResult != null) {
                    val idx = chunkIndex++
                    val peak = chunkResult.peakAmplitude
                    launch(Dispatchers.IO) {
                        try {
                            val text = transcribeChunk(chunkResult.file, peak)
                            chunkResult.file.delete()
                            if (!text.isNullOrBlank()) {
                                val raw = text.trim()
                                val preview = withContext(Dispatchers.Main) {
                                    liveChunks.add(raw)
                                    liveChunks.joinToString(" ").also { liveTextView?.text = it }
                                }
                                FocusTextInjector.updateDictationAsync(preview)
                                if (ramblerEnabled) {
                                    val joined = liveChunks.joinToString(" ")
                                    val cleaned = maybeRambler(joined)
                                    if (cleaned != joined && cleaned.isNotBlank()) {
                                        withContext(Dispatchers.Main) {
                                            liveChunks.clear()
                                            liveChunks.add(cleaned)
                                            liveTextView?.text = cleaned
                                        }
                                        FocusTextInjector.updateDictationAsync(cleaned)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            chunkResult.file.delete()
                            Log.w(TAG, "Chunk #$idx failed: ${e.message}")
                        }
                    }
                }
                delay(chunkIntervalMs)
            }
        }
    }

    private fun startDurationUpdates() {
        durationJob = scope.launch {
            while (isActive && isRecording) {
                val ms = audioRecorder?.durationMs?.value ?: 0L
                durationView?.text = AudioRecorder.formatDuration(ms)
                delay(100)
            }
        }
    }

    private suspend fun transcribeChunk(file: java.io.File, peak: Float): String? {
        return when (inferenceMode) {
            "LLM_GATEWAY" -> {
                if (peak <= 0.02f) return null
                val api = WhisperApi("")
                val result = api.transcribeViaGateway(file, gatewayUrl, "whisper-large-v3-turbo", language)
                result.text.takeIf { it.isNotBlank() }
            }
            else -> {
                val api = WhisperApi(serverUrl)
                api.transcribeChunk(file, language, task, peak)
            }
        }
    }

    private suspend fun transcribeFull(file: java.io.File): String {
        return when (inferenceMode) {
            "LLM_GATEWAY" -> {
                val api = WhisperApi("")
                val result = api.transcribeViaGateway(file, gatewayUrl, "whisper-large-v3-turbo", language)
                if (!result.success) throw Exception(result.error ?: "Gateway failed")
                result.text.trim()
            }
            else -> {
                val api = WhisperApi(serverUrl)
                val result = api.transcribe(file, language, task)
                if (!result.success) throw Exception(result.error ?: "Transcription failed")
                result.text.trim()
            }
        }
    }

    private suspend fun maybeRambler(text: String): String {
        if (!ramblerEnabled || text.isBlank()) return text
        val api = WhisperApi("")
        val cleaned = api.cleanRamblerText(gatewayUrl, text)
        return cleaned?.trim()?.takeIf { it.isNotBlank() } ?: text
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = service.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperFlow::VoiceKeyboard").apply {
                acquire(5 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
