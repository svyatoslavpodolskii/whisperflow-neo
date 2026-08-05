package com.pimax.whisperflow

import android.Manifest
import com.pimax.whisperflow.local.LocalWhisperEngine
import com.pimax.whisperflow.local.ModelManager
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect

private const val TAG = "🎙️ WhisperIME"
private const val CHUNK_INTERVAL_MS = 3000L
private const val CHUNK_INITIAL_DELAY_MS = 2500L

class WhisperInputService : InputMethodService() {

    private var audioRecorder: AudioRecorder? = null
    private var serviceScope: CoroutineScope? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isRecording = false
    private var isProcessing = false
    private var liveChunks = mutableListOf<String>()
    private var ramblerEnabled = false
    private var chunkJob: Job? = null
    private var durationJob: Job? = null

    // Local inference support
    private var localEngine: LocalWhisperEngine? = null
    private var inferenceMode: String = "GPU_SERVER"
    // Language token for forcing transcription language in local mode
    private var localLanguageToken: Int? = null

    // Views
    private var btnMic: ImageButton? = null
    private var btnSwitch: ImageButton? = null
    private var tvStatus: TextView? = null
    private var tvLivePreview: TextView? = null
    private var tvDuration: TextView? = null
    private var livePreviewContainer: LinearLayout? = null
    private var processingContainer: LinearLayout? = null
    private var viewGlow: View? = null
    private var viewRecordingDot: View? = null
    private var micButtonBgAnimator: ValueAnimator? = null
    private var dotAnimator: ObjectAnimator? = null
    private var glowAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        audioRecorder = AudioRecorder(this)
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        SoundFeedback.initialize(this)

        // Sync sound and vibration feedback settings in real-time
        serviceScope?.launch {
            val settings = SettingsDataStore(this@WhisperInputService)
            launch {
                settings.soundEnabled.collect {
                    SoundFeedback.soundEnabled = it
                }
            }
            launch {
                settings.vibrationEnabled.collect {
                    SoundFeedback.vibrationEnabled = it
                }
            }
            launch {
                settings.vibrationIntensity.collect {
                    SoundFeedback.vibrationIntensity = it
                }
            }
        }

        Log.d(TAG, "IME service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording(commitFinal = false)
        audioRecorder?.release()
        audioRecorder = null
        localEngine?.close()
        localEngine = null
        serviceScope?.cancel()
        serviceScope = null
        micButtonBgAnimator?.cancel()
        dotAnimator?.cancel()
        glowAnimator?.cancel()
        SoundFeedback.release()
        Log.d(TAG, "IME service destroyed")
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)

        btnMic = view.findViewById(R.id.btn_mic)
        btnSwitch = view.findViewById(R.id.btn_switch_keyboard)
        tvStatus = view.findViewById(R.id.tv_status)
        tvLivePreview = view.findViewById(R.id.tv_live_preview)
        tvDuration = view.findViewById(R.id.tv_duration)
        livePreviewContainer = view.findViewById(R.id.live_preview_container)
        processingContainer = view.findViewById(R.id.processing_container)
        viewGlow = view.findViewById(R.id.view_glow)
        viewRecordingDot = view.findViewById(R.id.view_recording_dot)

        btnMic?.setOnClickListener { onMicPressed() }
        tvStatus?.setOnClickListener { onMicPressed() }

        btnSwitch?.setOnClickListener {
            switchKeyboard()
        }

        updateUI()
        return view
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        WhisperInputBridge.activeIme = this
    }

    override fun onFinishInput() {
        if (WhisperInputBridge.activeIme == this) WhisperInputBridge.activeIme = null
        super.onFinishInput()
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!isRecording && !isProcessing) {
            tvStatus?.text = "Tap mic to transcribe"
            livePreviewContainer?.visibility = View.GONE
            processingContainer?.visibility = View.GONE
        }

        // Auto-start recording when triggered by side key
        if (VoiceKeyboardActions.pendingAutoRecord) {
            VoiceKeyboardActions.pendingAutoRecord = false
            VoiceKeyboardActions.wasAutoTriggered = true
            if (!isRecording && !isProcessing) {
                mainHandler.postDelayed({
                    if (!isRecording && !isProcessing && hasMicPermission()) {
                        Log.d(TAG, "Auto-starting recording from side key trigger")
                        startRecording()
                    }
                }, 400)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isRecording) {
            stopRecording(commitFinal = true)
        }
    }

    private fun onMicPressed() {
        if (!hasMicPermission()) {
            tvStatus?.text = "⚠ Open WhisperFlow app to grant mic permission"
            return
        }

        if (isProcessing) return

        if (isRecording) {
            stopRecording(commitFinal = true)
        } else {
            startRecording()
        }
    }

    // ── External control (called by VoiceKeyboardTriggerActivity) ───────

    fun isCurrentlyRecording(): Boolean = isRecording

    fun startRecordingExternal() {
        mainHandler.post {
            if (!isRecording && !isProcessing && hasMicPermission()) {
                Log.d(TAG, "External start recording (side key)")
                VoiceKeyboardActions.wasAutoTriggered = true
                startRecording()
            }
        }
    }

    fun stopRecordingExternal() {
        mainHandler.post {
            if (isRecording) {
                Log.d(TAG, "External stop recording (side key)")
                stopRecording(commitFinal = true)
            }
        }
    }

    private fun startRecording() {
        val recorder = audioRecorder ?: return
        if (recorder.state.value == AudioRecorder.State.RECORDING) return

        liveChunks.clear()
        isRecording = true

        // Initialize local engine if needed
        serviceScope?.launch {
            val settings = SettingsDataStore(this@WhisperInputService)
            inferenceMode = settings.inferenceMode.first()
            ramblerEnabled = settings.ramblerEnabled.first()

            if (inferenceMode == "LOCAL" && localEngine == null) {
                try {
                    val localModelName = settings.localModel.first()
                    val modelManager = ModelManager(this@WhisperInputService)
                    val model = try {
                        ModelManager.WhisperModel.valueOf(localModelName)
                    } catch (e: Exception) {
                        ModelManager.WhisperModel.TINY
                    }

                    if (modelManager.isModelInstalled(model) && modelManager.isVocabInstalled()) {
                        localEngine = withContext(Dispatchers.IO) {
                            LocalWhisperEngine.create(
                                context = this@WhisperInputService,
                                modelPath = modelManager.getModelFile(model).absolutePath,
                                vocabPath = modelManager.getVocabFile().absolutePath,
                                isMultilingual = true
                            )
                        }
                        Log.d(TAG, "Local engine initialized: ${model.displayName}")

                        // Resolve language token for local forced-language transcription
                        val langSetting = settings.language.first()
                        localLanguageToken = localEngine?.vocab?.languageTokenFor(
                            when (langSetting.lowercase()) {
                                "auto" -> ""
                                "english" -> "en"; "german" -> "de"; "chinese" -> "zh"
                                "spanish" -> "es"; "french" -> "fr"; "italian" -> "it"
                                "portuguese" -> "pt"; "russian" -> "ru"; "japanese" -> "ja"
                                "korean" -> "ko"; "arabic" -> "ar"; "hindi" -> "hi"
                                else -> if (langSetting.length == 2) langSetting.lowercase() else ""
                            }
                        )
                        Log.d(TAG, "Local language token: $localLanguageToken (from setting: $langSetting)")
                    } else {
                        Log.w(TAG, "Local model not installed, falling back to GPU")
                        inferenceMode = "GPU_SERVER"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to init local engine: ${e.message}")
                    inferenceMode = "GPU_SERVER"
                }
            }
        }

        SoundFeedback.playStart()
        val started = recorder.start()
        if (!started) {
            isRecording = false
            tvStatus?.text = "⚠ Could not start recording"
            Log.e(TAG, "Failed to start recording")
            return
        }

        Log.d(TAG, "Recording started (mode: $inferenceMode)")
        updateUI()

        // No live chunking for LLM Gateway mode
        if (inferenceMode != "LLM_GATEWAY") {
            startLiveChunking()
        }

        startDurationUpdates()
    }

    private fun stopRecording(commitFinal: Boolean) {
        if (!isRecording) return
        isRecording = false
        SoundFeedback.playStop()

        chunkJob?.cancel()
        chunkJob = null
        durationJob?.cancel()
        durationJob = null
        micButtonBgAnimator?.cancel()
        dotAnimator?.cancel()

        val recorder = audioRecorder ?: return
        val wavFile = recorder.stop()

        if (commitFinal && wavFile != null) {
            isProcessing = true
            updateUI()

            serviceScope?.launch {
                try {
                    val settings = SettingsDataStore(this@WhisperInputService)
                    val language = settings.language.first()
                    val task = settings.task.first()

                    val resultText: String
                    val durationMs: Long

                    if (inferenceMode == "LOCAL" && localEngine != null) {
                        // Local inference
                        val samples = audioFileToFloatSamples(wavFile)
                        val localResult = localEngine!!.transcribe(samples, localLanguageToken)
                        resultText = localResult.text
                        durationMs = localResult.processingTimeMs
                        Log.d(TAG, "Local inference completed in ${durationMs}ms")
                    } else if (inferenceMode == "LLM_GATEWAY") {
                        // LLM Gateway
                        val gatewayUrl = settings.gatewayUrl.first()
                        val api = WhisperApi("")
                        val result = api.transcribeViaGateway(wavFile, gatewayUrl, "whisper-large-v3-turbo", language)
                        if (!result.success) {
                            throw Exception(result.error ?: "Gateway transcription failed")
                        }
                        resultText = result.text
                        durationMs = result.durationMs
                    } else {
                        // GPU Server
                        val serverUrl = settings.serverUrl.first()
                        val api = WhisperApi(serverUrl)
                        val result = api.transcribe(wavFile, language, task)
                        if (!result.success) {
                            throw Exception(result.error ?: "Transcription failed")
                        }
                        resultText = result.text
                        durationMs = result.durationMs
                    }

                    isProcessing = false

                    if (resultText.isNotBlank()) {
                        val rawText = resultText.trim()
                        var finalText = rawText
                        commitText(rawText)

                        if (ramblerEnabled) {
                            val gatewayUrl = settings.gatewayUrl.first()
                            val api = WhisperApi("")
                            val cleaned = api.cleanRamblerText(gatewayUrl, rawText)
                            if (cleaned != null && cleaned.trim() != rawText) {
                                finalText = cleaned.trim()
                                replaceLastText(rawText, finalText)
                            }
                        }

                        settings.updateLastTranscription(finalText)
                        val audioPath = settings.saveRecordingPermanently(wavFile)
                        val modeStr = when (inferenceMode) {
                            "LOCAL" -> "LOCAL:${settings.localModel.first()}"
                            "LLM_GATEWAY" -> "GATEWAY:whisper-large-v3-turbo"
                            else -> "GPU_SERVER"
                        }
                        settings.addToHistory(finalText, durationMs, language, audioPath, modeStr, false)
                        SoundFeedback.playSuccess()
                        Log.d(TAG, "✅ Final transcription committed: ${finalText.take(60)}...")

                        // Auto-restore previous IME after side key session
                        if (VoiceKeyboardActions.wasAutoTriggered) {
                            VoiceKeyboardActions.wasAutoTriggered = false
                            mainHandler.postDelayed({
                                ImeSwitch.restorePreviousIme(applicationContext)
                                Log.d(TAG, "Auto-restored previous IME after side key session")
                            }, 600)
                        }
                    } else {
                        SoundFeedback.playError()
                        Log.e(TAG, "Empty transcription result")
                        tvStatus?.text = "⚠ No speech detected"
                    }
                } catch (e: Exception) {
                    isProcessing = false
                    Log.e(TAG, "Transcription failed: ${e.message}")
                    SoundFeedback.playError()
                    tvStatus?.text = "⚠ ${e.message ?: "Connection failed"}"
                }

                updateUI()
            }
        } else {
            updateUI()
        }
    }

    private fun startLiveChunking() {
        chunkJob = serviceScope?.launch {
            delay(CHUNK_INITIAL_DELAY_MS)
            while (isActive && isRecording) {
                val chunkResult = audioRecorder?.saveChunk()
                if (chunkResult != null) {
                    val settings = SettingsDataStore(this@WhisperInputService)
                    val language = settings.language.first()
                    val task = settings.task.first()
                    val peak = chunkResult.peakAmplitude

                    launch(Dispatchers.IO) {
                        try {
                            val text: String? = if (inferenceMode == "LOCAL" && localEngine != null) {
                                // Local inference for chunks
                                val samples = audioFileToFloatSamples(chunkResult.file)
                                if (peak > 0.02f && samples.isNotEmpty()) {
                                    val result = localEngine!!.transcribe(samples, localLanguageToken)
                                    result.text.takeIf { it.isNotBlank() }
                                } else null
                            } else if (inferenceMode == "LLM_GATEWAY") {
                                // LLM Gateway for chunks
                                if (peak > 0.02f) {
                                    val gatewayUrl = settings.gatewayUrl.first()
                                    val api = WhisperApi("")
                                    val result = api.transcribeViaGateway(chunkResult.file, gatewayUrl, "whisper-large-v3-turbo", language)
                                    result.text.takeIf { it.isNotBlank() }
                                } else null
                            } else {
                                // GPU Server
                                val serverUrl = settings.serverUrl.first()
                                val api = WhisperApi(serverUrl)
                                api.transcribeChunk(chunkResult.file, language, task, peak)
                            }
                            chunkResult.file.delete()

                            if (!text.isNullOrBlank()) {
                                val rawChunk = text.trim()
                                withContext(Dispatchers.Main) {
                                    liveChunks.add(rawChunk)
                                    val preview = liveChunks.joinToString(" ")
                                    tvLivePreview?.text = preview
                                    tvLivePreview?.visibility = View.VISIBLE
                                    livePreviewContainer?.visibility = View.VISIBLE
                                    viewRecordingDot?.visibility = View.VISIBLE

                                    // Also commit live chunks as they arrive
                                    commitText(rawChunk + " ")
                                    Log.d(TAG, "Live chunk committed: ${rawChunk.take(40)}...")
                                }

                                if (ramblerEnabled) {
                                    val gatewayUrl = settings.gatewayUrl.first()
                                    val api = WhisperApi("")
                                    val cleaned = api.cleanRamblerText(gatewayUrl, rawChunk)
                                    if (cleaned != null && cleaned.trim() != rawChunk) {
                                        val cleanedChunk = cleaned.trim()
                                        withContext(Dispatchers.Main) {
                                            // Replace raw chunk with cleaned chunk in text field
                                            replaceLastText(rawChunk + " ", cleanedChunk + " ")

                                            // Update liveChunks list and tvLivePreview with nice flash animation
                                            if (liveChunks.isNotEmpty()) {
                                                liveChunks[liveChunks.size - 1] = cleanedChunk
                                                tvLivePreview?.text = liveChunks.joinToString(" ")
                                                animatePreviewFlash()
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Chunk failed: ${e.message}")
                            chunkResult.file.delete()
                        }
                    }
                }
                delay(CHUNK_INTERVAL_MS)
            }
        }
    }

    private fun audioFileToFloatSamples(file: java.io.File): FloatArray {
        val bytes = file.readBytes()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)

        if (bytes.size <= 44) return FloatArray(0)
        buffer.position(44)

        val numSamples = (bytes.size - 44) / 2
        val samples = FloatArray(numSamples)

        for (i in 0 until numSamples) {
            if (buffer.remaining() >= 2) {
                val pcmValue = buffer.short
                samples[i] = pcmValue / 32768f
            }
        }

        return samples
    }

    private fun startDurationUpdates() {
        durationJob = serviceScope?.launch {
            while (isActive && isRecording) {
                val ms = audioRecorder?.durationMs?.value ?: 0L
                tvDuration?.text = AudioRecorder.formatDuration(ms)
                tvDuration?.visibility = View.VISIBLE
                delay(100)
            }
        }
    }

    private fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
    }

    private fun replaceLastText(oldText: String, newText: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(oldText.length, 0)
        ic.commitText(newText, 1)
        ic.endBatchEdit()
    }

    private fun animatePreviewFlash() {
        val tv = tvLivePreview ?: return
        val normalColor = tv.currentTextColor
        val flashColor = 0xFFFF8B00.toInt() // Amber
        val animator = ValueAnimator.ofArgb(flashColor, normalColor)
        animator.duration = 400
        animator.addUpdateListener { anim ->
            tv.setTextColor(anim.animatedValue as Int)
        }
        animator.start()
    }

    private fun updateUI() {
        mainHandler.post {
            when {
                isRecording -> {
                    tvStatus?.text = "Listening..."
                    tvStatus?.setTextColor(0xFFFF8B00.toInt())
                    btnMic?.setBackgroundResource(R.drawable.mic_button_recording_bg)
                    livePreviewContainer?.visibility = View.VISIBLE
                    processingContainer?.visibility = View.GONE
                    viewRecordingDot?.visibility = View.VISIBLE
                    tvDuration?.visibility = View.VISIBLE
                    startPulseAnimation()
                    startDotAnimation()
                }
                isProcessing -> {
                    tvStatus?.text = "Finalizing..."
                    tvStatus?.setTextColor(0xFFFF8B00.toInt())
                    btnMic?.setBackgroundResource(R.drawable.mic_button_bg)
                    livePreviewContainer?.visibility = View.GONE
                    processingContainer?.visibility = View.VISIBLE
                    viewRecordingDot?.visibility = View.GONE
                    tvDuration?.visibility = View.GONE
                    stopPulseAnimation()
                    stopDotAnimation()
                }
                else -> {
                    tvStatus?.text = "Tap mic to transcribe"
                    tvStatus?.setTextColor(0xFF7A7580.toInt())
                    btnMic?.setBackgroundResource(R.drawable.mic_button_bg)
                    livePreviewContainer?.visibility = View.GONE
                    processingContainer?.visibility = View.GONE
                    viewRecordingDot?.visibility = View.GONE
                    tvDuration?.visibility = View.GONE
                    tvLivePreview?.text = ""
                    stopPulseAnimation()
                    stopDotAnimation()
                }
            }
        }
    }

    private fun startPulseAnimation() {
        micButtonBgAnimator?.cancel()
        glowAnimator?.cancel()
        val btn = btnMic ?: return
        val glow = viewGlow ?: return

        glow.visibility = View.VISIBLE
        glow.alpha = 0.4f

        micButtonBgAnimator = ValueAnimator.ofFloat(1f, 1.12f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                btn.scaleX = scale
                btn.scaleY = scale
            }
            start()
        }

        glowAnimator = ValueAnimator.ofFloat(1f, 1.4f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                glow.scaleX = scale
                glow.scaleY = scale
                val progress = (scale - 1f) / 0.4f
                glow.alpha = 0.4f * (1f - progress)
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        micButtonBgAnimator?.cancel()
        micButtonBgAnimator = null
        glowAnimator?.cancel()
        glowAnimator = null
        btnMic?.scaleX = 1f
        btnMic?.scaleY = 1f
        viewGlow?.visibility = View.GONE
    }

    private fun startDotAnimation() {
        dotAnimator?.cancel()
        val dot = viewRecordingDot ?: return
        dotAnimator = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f).apply {
            duration = 700
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopDotAnimation() {
        dotAnimator?.cancel()
        dotAnimator = null
        viewRecordingDot?.alpha = 1f
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun switchKeyboard() {
        try {
            val token = window?.window?.attributes?.token
            if (token != null) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.switchToLastInputMethod(token)
            } else {
                switchToPreviousInputMethod()
            }
        } catch (_: Exception) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showInputMethodPicker()
        }
    }
}
