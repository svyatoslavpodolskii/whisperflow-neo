package com.pimax.whisperflow

import android.app.VoiceInteractor
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.pimax.whisperflow.local.LocalWhisperEngine
import com.pimax.whisperflow.local.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.graphics.Path
import android.graphics.PathMeasure
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import kotlin.math.PI
import kotlin.math.sin

private const val TAG = "🎙️ WhisperAssist"
private const val CHUNK_INTERVAL_MS = 1500L
private const val CHUNK_INITIAL_DELAY_MS = 1000L
private const val SILENCE_THRESHOLD = 0.02f        // amplitude below this = silence
private const val SPEECH_THRESHOLD = 0.04f          // amplitude above this = speech detected
private const val SILENCE_TIMEOUT_MS = 1800L        // auto-stop after this much silence post-speech
private const val MIN_RECORD_BEFORE_SILENCE = 1500L // min recording time before silence detection kicks in

// ═══════════════════════════════════════════════════════════════
// 🎤 VOICE INTERACTION SERVICE — registered as digital assistant
// ═══════════════════════════════════════════════════════════════

class WhisperAssistService : VoiceInteractionService() {

    override fun showSession(args: Bundle?, flags: Int) {
        if (VoiceKeyboardActions.wasRecentlyTriggered()) {
            Log.d(TAG, "Suppressing assistant session — voice keyboard recently triggered")
            return
        }
        super.showSession(args, flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.pimax.whisperflow.ACTION_PTT_START" -> {
                Log.d(TAG, "PTT START received → showing session")
                showSession(Bundle(), 0)
            }
            "com.pimax.whisperflow.ACTION_VOICE_KEYBOARD_START" -> {
                Log.d(TAG, "Voice keyboard START received → routing to accessibility overlay")
                WhisperAccessibilityService.instance?.showVoiceKeyboardOverlay()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "WhisperAssist service ready — long-press power to activate")

        // Pre-load model in background so first activation is instant
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val settings = SettingsDataStore(applicationContext)
                val mode = settings.inferenceMode.first()
                if (mode == "LOCAL") {
                    val localModelName = settings.localModel.first()
                    val modelManager = ModelManager(applicationContext)
                    val model = try {
                        ModelManager.WhisperModel.valueOf(localModelName)
                    } catch (e: Exception) {
                        ModelManager.WhisperModel.TINY
                    }
                    if (modelManager.isModelInstalled(model) && modelManager.isVocabInstalled()) {
                        if (WhisperAssistSession.cachedEngine == null || WhisperAssistSession.cachedModelName != localModelName) {
                            WhisperAssistSession.cachedEngine?.close()
                            WhisperAssistSession.cachedEngine = LocalWhisperEngine.create(
                                context = applicationContext,
                                modelPath = modelManager.getModelFile(model).absolutePath,
                                vocabPath = modelManager.getVocabFile().absolutePath,
                                isMultilingual = true
                            )
                            WhisperAssistSession.cachedModelName = localModelName
                            Log.d(TAG, "✅ Pre-loaded model: ${model.displayName}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pre-load failed (will load on first use): ${e.message}")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 🔌 SESSION SERVICE — creates the session when assistant launches
// ═══════════════════════════════════════════════════════════════

class WhisperAssistSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return WhisperAssistSession(this)
    }
}

// ═══════════════════════════════════════════════════════════════
// 🎙️ ASSIST SESSION — the overlay UI that appears on long-press
// ═══════════════════════════════════════════════════════════════

class WhisperAssistSession(context: Context) : VoiceInteractionSession(context) {

    private var audioRecorder: AudioRecorder? = null
    private var sessionScope: CoroutineScope? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Wake lock — prevents CPU from sleeping during recording/transcription
    private var wakeLock: PowerManager.WakeLock? = null

    private var isRecording = false
    private var isProcessing = false
    private var liveChunks = mutableListOf<String>()
    private var chunkJob: Job? = null
    private var durationJob: Job? = null
    private var silenceJob: Job? = null

    // Local inference support
    private var localEngine: LocalWhisperEngine? = null
    private var inferenceMode: String = "GPU_SERVER"
    private var assistantStopMode: String = SettingsDataStore.ASSISTANT_STOP_SILENCE

    // Language token for forcing transcription language in local mode
    private var localLanguageToken: Int? = null

    // Configurable timing (read from settings in onShow)
    private var chunkIntervalMs: Long = CHUNK_INTERVAL_MS
    private var silenceTimeoutMs: Long = SILENCE_TIMEOUT_MS
    private var liveChunkingEnabled: Boolean = true
    private var ramblerEnabled: Boolean = false
    private var voiceKeyboardSession: Boolean = false

    // Views
    private var rootLayout: LinearLayout? = null
    private var waveformView: AssistWaveformView? = null
    private var tvStatus: TextView? = null
    private var tvDuration: TextView? = null
    private var tvLiveText: TextView? = null
    private var tvModeChip: TextView? = null
    private var voiceKbHeader: TextView? = null
    private var processingBar: ProgressBar? = null
    private var inferenceGraphView: AssistInferenceGraphView? = null
    private var rippleView: AssistRippleView? = null
    private var wrapper: FrameLayout? = null
    private var snakeView: SnakeView? = null
    private var treeView: TreeView? = null
    private var cardExpandView: CardExpandView? = null
    private var assistantAnimationMode: String = SettingsDataStore.ANIMATION_MODE_SNAKE

    private val pttReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.pimax.whisperflow.ACTION_PTT_STOP") {
                Log.d(TAG, "PTT STOP received → finishing recording")
                if (isRecording) stopRecording(commitFinal = true)
            }
        }
    }

    companion object {
        // Persistent model cache — survives across sessions
        var cachedEngine: LocalWhisperEngine? = null
        var cachedModelName: String? = null
        var cachedLanguageToken: Int? = null
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        setKeepAwake(true)

        audioRecorder = AudioRecorder(context)
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        SoundFeedback.initialize(context)

        val settings = SettingsDataStore(context)
        voiceKeyboardSession = runBlocking {
            settings.consumeVoiceKeyboardSessionPending()
        } || args?.getBoolean("voice_keyboard", false) == true

        Log.d(TAG, "Session shown — voiceKb=$voiceKeyboardSession")

        sessionScope?.launch {
            launch { settings.soundEnabled.collect { SoundFeedback.soundEnabled = it } }
            launch { settings.vibrationEnabled.collect { SoundFeedback.vibrationEnabled = it } }
            launch { settings.vibrationIntensity.collect { SoundFeedback.vibrationIntensity = it } }
        }

        val filter = android.content.IntentFilter("com.pimax.whisperflow.ACTION_PTT_STOP")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(pttReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(pttReceiver, filter)
        }

        val animSpeed = runBlocking { settings.assistantAnimationSpeed.first() }
        snakeView?.animationSpeed = animSpeed
        treeView?.animationSpeed = animSpeed
        cardExpandView?.animationSpeed = animSpeed

        // Pass camera cutout position to CardExpandView from the Window (available here)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val window = getWindow()?.window
                val cutout = window?.decorView?.rootWindowInsets?.displayCutout
                val rects = cutout?.boundingRects
                if (!rects.isNullOrEmpty()) {
                    val rect = rects[0]
                    cardExpandView?.setCutoutOrigin(
                        rect.centerX().toFloat(),
                        rect.centerY().toFloat(),
                        kotlin.math.min(rect.width(), rect.height()) / 2f
                    )
                    Log.d(TAG, "🎨 Cutout rect: $rect")
                }
            } catch (e: Exception) {
                Log.d(TAG, "🎨 Cutout not available from window: ${e.message}")
            }
        }

        liveChunks.clear()
        isProcessing = false
        tvLiveText?.text = ""
        tvDuration?.text = ""
        findProcessingContainer()?.visibility = View.GONE
        inferenceGraphView?.setInferenceActive(false)

        if (voiceKeyboardSession) {
            val fieldReady = FocusTextInjector.beginSession()
            if (!fieldReady) {
                Log.w(TAG, "Voice keyboard: no focused editable field found")
            }
            applyVoiceKeyboardPresentation()
            if (!fieldReady) {
                voiceKbHeader?.text = "⚠ Tap a text field first, then trigger again"
                voiceKbHeader?.setTextColor(0xFFFF6666.toInt())
            } else {
                voiceKbHeader?.text = "⌨  VOICE KEYBOARD — speaking into focused field"
                voiceKbHeader?.setTextColor(0xFFFF8B00.toInt())
            }
        } else {
            applyAssistantPresentation()
            tvLiveText?.visibility = View.GONE
            tvStatus?.text = "LISTENING"
            tvStatus?.setTextColor(0xCCFF3333.toInt())
        }

        sessionScope?.launch {
            try {
                inferenceMode = settings.inferenceMode.first()
                assistantStopMode = settings.assistantStopMode.first()
                assistantAnimationMode = settings.assistantAnimationMode.first()
                chunkIntervalMs = settings.chunkIntervalMs.first()
                silenceTimeoutMs = settings.silenceTimeoutMs.first()
                liveChunkingEnabled = settings.liveChunkingEnabled.first()
                ramblerEnabled = settings.ramblerEnabled.first()
                val serverUrl = settings.serverUrl.first()
                Log.d(TAG, "Assistant config: mode=$inferenceMode, stopMode=$assistantStopMode, live=$liveChunkingEnabled, rambler=$ramblerEnabled, voiceKb=$voiceKeyboardSession")

                if (inferenceMode == "LOCAL") {
                    // Initialize local engine
                    val localModelName = settings.localModel.first()
                    val modelManager = ModelManager(context)
                    val model = try {
                        ModelManager.WhisperModel.valueOf(localModelName)
                    } catch (e: Exception) {
                        ModelManager.WhisperModel.TINY
                    }

                    if (modelManager.isModelInstalled(model) && modelManager.isVocabInstalled()) {
                        // Use cached engine if available and same model
                        if (cachedEngine != null && cachedModelName == localModelName) {
                            localEngine = cachedEngine
                            Log.d(TAG, "♻️ Reusing cached engine: $localModelName")
                        } else {
                            cachedEngine?.close()
                            val engine = withContext(Dispatchers.IO) {
                                LocalWhisperEngine.create(
                                    context = context,
                                    modelPath = modelManager.getModelFile(model).absolutePath,
                                    vocabPath = modelManager.getVocabFile().absolutePath,
                                    isMultilingual = true
                                )
                            }
                            cachedEngine = engine
                            cachedModelName = localModelName
                            localEngine = engine
                            Log.d(TAG, "🔄 Created and cached new engine: $localModelName")
                        }
                        // Update mode chip to show model name (keep red theme)
                        tvModeChip?.text = model.displayName.uppercase()
                        tvModeChip?.setTextColor(0xFFFF5555.toInt())
                        (tvModeChip?.background as? android.graphics.drawable.GradientDrawable)?.apply {
                            setColor(0xEE0D0D12.toInt())
                            setStroke(dp(1), 0x88FF3333.toInt())
                        }
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
                        tvStatus?.text = "LISTENING"
                        tvStatus?.setTextColor(0xCCFF3333.toInt())
                        mainHandler.postDelayed({ startRecording() }, 150)
                    }
                } else if (inferenceMode == "LLM_GATEWAY") {
                    // LLM Gateway mode
                    tvModeChip?.text = "GATEWAY (GROQ)"
                    tvModeChip?.setTextColor(0xFFFFCC33.toInt()) // Amber-ish
                    (tvModeChip?.background as? android.graphics.drawable.GradientDrawable)?.apply {
                        setColor(0xEE0D0D12.toInt())
                        setStroke(dp(1), 0x88FFCC33.toInt())
                    }

                    val gatewayUrl = settings.gatewayUrl.first()
                    val api = WhisperApi(serverUrl) // Base URL doesn't matter for gateway call in WhisperApi
                    // We'll trust the gateway is up or let the first chunk/commit fail
                    mainHandler.postDelayed({ startRecording() }, 150)
                } else {
                    // GPU Server mode - check health
                    // Update mode chip to show GPU SERVER (keep red theme)
                    tvModeChip?.text = "GPU SERVER"
                    tvModeChip?.setTextColor(0xFFFF5555.toInt())
                    (tvModeChip?.background as? android.graphics.drawable.GradientDrawable)?.apply {
                        setColor(0xEE0D0D12.toInt())
                        setStroke(dp(1), 0x88FF3333.toInt())
                    }

                    val api = WhisperApi(serverUrl)
                    val isOnline = withContext(Dispatchers.IO) { api.checkHealth() }

                    if (isOnline) {
                        mainHandler.postDelayed({ startRecording() }, 150)
                    } else {
                        tvStatus?.text = "⚠ GPU OFFLINE"
                        tvStatus?.setTextColor(0xFFFF4444.toInt())
                        Log.w(TAG, "GPU server offline — recording blocked")
                        mainHandler.postDelayed({ dismissAssist() }, 2000)
                    }
                }
            } catch (e: Exception) {
                tvStatus?.text = "⚠ INIT FAILED"
                tvStatus?.setTextColor(0xFFFF4444.toInt())
                Log.w(TAG, "Initialization failed: ${e.message}")
                mainHandler.postDelayed({ dismissAssist() }, 2000)
            }
        }
    }

    override fun onHide() {
        super.onHide()
        val wasVoiceKb = voiceKeyboardSession
        try {
            context.unregisterReceiver(pttReceiver)
        } catch (e: Exception) {}

        val wasRecording = isRecording
        var wavFile: java.io.File? = null

        // Stop recording synchronously to capture the WAV file
        if (isRecording) {
            isRecording = false
            SoundFeedback.playStop()
            chunkJob?.cancel()
            chunkJob = null
            durationJob?.cancel()
            durationJob = null
            silenceJob?.cancel()
            silenceJob = null
            waveformView?.stopAnimation()
            wavFile = audioRecorder?.stop()
            releaseWakeLock()
        }

        // Capture engine BEFORE cleanup (we may need it for background transcription)
        val currentMode = inferenceMode
        val currentEngine = localEngine

        // Clean up session resources
        audioRecorder?.release()
        audioRecorder = null
        // Engine is cached in companion object — don't close it
        localEngine = null
        sessionScope?.cancel()
        sessionScope = null
        waveformView?.stopAnimation()
        // Ensure wake lock is released even if recording wasn't active
        releaseWakeLock()

        // If recording was active (power button pressed again), commit in background
        if (wasRecording && wavFile != null) {
            val ctx = context.applicationContext
            val file = wavFile
            Log.d(TAG, "Session hidden while recording — committing in background (mode=$currentMode, voiceKb=$wasVoiceKb, hasEngine=${currentEngine != null})")
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val settings = SettingsDataStore(ctx)
                    val language = settings.language.first()
                    val task = settings.task.first()

                    val resultText: String
                    val durationMs: Long

                    if (currentMode == "LOCAL" && currentEngine != null) {
                        // Local inference
                        val samples = audioFileToFloatSamples(file)
                        val localResult = currentEngine.transcribe(samples, localLanguageToken)
                        resultText = localResult.text
                        durationMs = localResult.processingTimeMs
                    } else if (currentMode == "LLM_GATEWAY") {
                        // LLM Gateway
                        val gatewayUrl = settings.gatewayUrl.first()
                        val api = WhisperApi("")
                        val result = api.transcribeViaGateway(file, gatewayUrl, "whisper-large-v3-turbo", language)
                        if (!result.success) {
                            throw Exception(result.error ?: "Gateway transcription failed")
                        }
                        resultText = result.text
                        durationMs = result.durationMs
                    } else {
                        // GPU Server
                        val serverUrl = settings.serverUrl.first()
                        val api = WhisperApi(serverUrl)
                        val result = api.transcribe(file, language, task)
                        if (!result.success) {
                            throw Exception(result.error ?: "Transcription failed")
                        }
                        resultText = result.text
                        durationMs = result.durationMs
                    }

                    if (resultText.isNotBlank()) {
                        val finalText = resultText.trim()
                        settings.updateLastTranscription(finalText)
                        val audioPath = settings.saveRecordingPermanently(file)
                        val modeStr = when (inferenceMode) {
                            "LOCAL" -> "LOCAL:${settings.localModel.first()}"
                            "LLM_GATEWAY" -> "GATEWAY:whisper-large-v3-turbo"
                            else -> "GPU_SERVER"
                        }

                        // Send to Tasks if enabled
                        var sentToTasks = false
                        if (!wasVoiceKb && settings.tasksEnabled.first() && !settings.assistantEnabled.first() && finalText.isNotBlank()) {
                            val tasksUrl = settings.tasksUrl.first()
                            val tasksApi = TasksApi(tasksUrl)
                            val taskResult = tasksApi.createTask(finalText)
                            sentToTasks = taskResult.success
                            if (sentToTasks) SoundFeedback.playTaskSaved()
                        }

                        // Voice Assistant (skip direct Streak when ON)
                        var assistantUsed = false
                        var assistantMessage: String? = null
                        if (!wasVoiceKb && settings.assistantEnabled.first() && finalText.isNotBlank()) {
                            assistantUsed = true
                            val assistantUrl = settings.assistantUrl.first()
                            val assistantApi = WhisperHookApi(assistantUrl, "Assistant")
                            val hookResult = assistantApi.send(finalText)
                            assistantMessage = hookResult.message.ifBlank { "⚠️ Assistant unreachable" }
                        }

                        var streakMessage: String? = null
                        if (!wasVoiceKb && !settings.assistantEnabled.first() && settings.streakEnabled.first() && finalText.isNotBlank()) {
                            val streakUrl = settings.streakUrl.first()
                            val streakApi = WhisperHookApi(streakUrl, "Streak")
                            val hookResult = streakApi.send(finalText)
                            if (hookResult.success && hookResult.action != "ignored") {
                                streakMessage = hookResult.message
                            }
                        }

                        settings.addToHistory(finalText, durationMs, language, audioPath, modeStr, sentToTasks)

                        withContext(Dispatchers.Main) {
                            if (wasVoiceKb) {
                                finishVoiceKeyboardSession(finalText, wasVoiceKb = true)
                                SoundFeedback.playSuccess()
                            } else {
                                copyTranscriptionUnlessAssistantAnswered(
                                    finalText,
                                    assistantMessage,
                                    streakMessage,
                                )
                                val toastParts = mutableListOf<String>()
                                if (sentToTasks) toastParts.add("✅ Task")
                                if (assistantMessage != null) toastParts.add(assistantMessage)
                                if (streakMessage != null) toastParts.add(streakMessage)
                                val toastMsg = when {
                                    toastParts.isNotEmpty() -> toastParts.joinToString(" • ")
                                    assistantUsed -> assistantMessage ?: "⚠️ Assistant: no response"
                                    else -> clipboardToast(ctx, finalText.take(70))
                                }
                                Toast.makeText(ctx, toastMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                        Log.d(TAG, "✅ Background transcription ($modeStr): ${finalText.take(60)}...")
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "⚠ Empty transcription", Toast.LENGTH_SHORT).show()
                        }
                        Log.e(TAG, "Background transcription returned empty text")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "⚠ ${e.message?.take(30) ?: "Failed"}", Toast.LENGTH_SHORT).show()
                    }
                    Log.e(TAG, "Background transcription failed: ${e.message}")
                } finally {
                    // Engine is cached — don't close it
                }
            }
        }

        if (!wasVoiceKb) {
            FocusTextInjector.endSession()
            voiceKeyboardSession = false
        }

        Log.d(TAG, "Assist session hidden")
    }

    override fun onCreateContentView(): View {
        val ctx = context

        rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 0)
        }

        voiceKbHeader = TextView(ctx).apply {
            text = "⌨  VOICE KEYBOARD"
            setTextColor(0xFFFF8B00.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        rootLayout?.addView(voiceKbHeader)

        waveformView = AssistWaveformView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(120)
            )
            isClickable = true
            setOnClickListener {
                if (isRecording && !isProcessing) stopRecording(commitFinal = true)
            }
        }
        rootLayout?.addView(waveformView)

        tvLiveText = TextView(ctx).apply {
            setTextColor(0xCCFFE0B2.toInt())
            textSize = 13f
            maxLines = 3
            visibility = View.GONE
            setPadding(dp(4), dp(6), dp(4), 0)
        }
        rootLayout?.addView(tvLiveText)

        tvDuration = TextView(ctx).apply {
            setTextColor(0x88FF8B00.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        rootLayout?.addView(tvDuration)

        // Processing row (small subtle label)
        val processingRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        processingBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFF8B00.toInt())
        }
        processingRow.addView(processingBar)

        val processingLabel = TextView(ctx).apply {
            text = "  Processing..."
            setTextColor(0x99FF3333.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        processingRow.addView(processingLabel)
        rootLayout?.addView(processingRow)

        // Wrapper — darker scrim with gradient for better visibility
        val w = FrameLayout(ctx).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x11000000.toInt(), 0xAA000000.toInt())
            )
            setOnClickListener { dismissAssist() }
        }
        wrapper = w

        // Ripple view behind content
        rippleView = AssistRippleView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        w.addView(rippleView)

        // Snake view on top of ripples
        snakeView = SnakeView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        w.addView(snakeView)

        // Tree view alternative
        treeView = TreeView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        w.addView(treeView)

        // Card expand view (Siri-style punch hole → card animation)
        cardExpandView = CardExpandView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        w.addView(cardExpandView)

        val contentParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(48)
        }

        rootLayout?.layoutParams = contentParams
        rootLayout?.setOnClickListener { /* consume — don't dismiss when tapping content */ }

        w.addView(rootLayout)

        return w
    }

    private fun applyVoiceKeyboardPresentation() {
        snakeView?.visibility = View.GONE
        treeView?.visibility = View.GONE
        cardExpandView?.visibility = View.GONE
        rippleView?.visibility = View.GONE

        wrapper?.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xCC0D0D18.toInt(), 0x00000000)
        )

        rootLayout?.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xF00D0D18.toInt())
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0xCCFF8B00.toInt())
        }
        rootLayout?.setPadding(dp(14), dp(10), dp(14), dp(10))

        (rootLayout?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(28)
            bottomMargin = 0
            marginStart = dp(12)
            marginEnd = dp(12)
            width = ViewGroup.LayoutParams.MATCH_PARENT
            rootLayout?.layoutParams = this
        }

        voiceKbHeader?.visibility = View.VISIBLE
        tvLiveText?.visibility = View.VISIBLE
        tvDuration?.visibility = View.VISIBLE

        rootLayout?.alpha = 0f
        rootLayout?.translationY = -dp(90).toFloat()
        rootLayout?.post {
            rootLayout?.animate()
                ?.alpha(1f)
                ?.translationY(0f)
                ?.setDuration(320)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
        }
    }

    private fun applyAssistantPresentation() {
        snakeView?.visibility = View.VISIBLE
        rippleView?.visibility = View.VISIBLE
        cardExpandView?.visibility = View.GONE
        voiceKbHeader?.visibility = View.GONE
        tvDuration?.visibility = View.GONE

        wrapper?.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x11000000.toInt(), 0xAA000000.toInt())
        )
        rootLayout?.background = null
        rootLayout?.setPadding(0, 0, 0, 0)

        (rootLayout?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            gravity = Gravity.BOTTOM
            topMargin = 0
            bottomMargin = dp(48)
            marginStart = 0
            marginEnd = 0
            width = ViewGroup.LayoutParams.MATCH_PARENT
            rootLayout?.layoutParams = this
        }

        rootLayout?.post {
            val h = wrapper?.height?.toFloat() ?: 2000f
            val waveformH = dp(120).toFloat()
            val marginBottom = dp(48).toFloat()
            val targetY = h - marginBottom - (waveformH / 2f)
            rootLayout?.alpha = 0f
            rootLayout?.translationY = 0f

            if (assistantAnimationMode == SettingsDataStore.ANIMATION_MODE_CARD) {
                // Card mode: expand from camera punch hole
                snakeView?.visibility = View.GONE
                treeView?.visibility = View.GONE
                rippleView?.visibility = View.GONE
                cardExpandView?.visibility = View.VISIBLE

                // For card mode, reposition rootLayout to top (inside card area)
                (rootLayout?.layoutParams as? FrameLayout.LayoutParams)?.apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = dp(72)  // below the card top edge
                    bottomMargin = 0
                    marginStart = dp(20)
                    marginEnd = dp(20)
                    rootLayout?.layoutParams = this
                }

                // Less scrim for card mode — card provides its own background
                wrapper?.background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00000000.toInt(), 0x33000000.toInt())
                )

                cardExpandView?.animationSpeed = snakeView?.animationSpeed ?: 1.0f
                cardExpandView?.start(targetY) {
                    waveformView?.emerge()
                    rootLayout?.alpha = 1f
                }
            } else if (assistantAnimationMode == SettingsDataStore.ANIMATION_MODE_TREE) {
                snakeView?.visibility = View.GONE
                cardExpandView?.visibility = View.GONE
                treeView?.start(targetY) {
                    // Instant swap: line is fully stretched → waveform starts flat (same visual)
                    waveformView?.emerge()
                    rootLayout?.alpha = 1f
                    treeView?.visibility = View.INVISIBLE
                    rippleView?.startRipple()
                }
            } else {
                treeView?.visibility = View.GONE
                cardExpandView?.visibility = View.GONE
                snakeView?.start(targetY) {
                    // Instant swap: line is fully stretched → waveform starts flat (same visual)
                    waveformView?.emerge()
                    rootLayout?.alpha = 1f
                    snakeView?.visibility = View.INVISIBLE
                    rippleView?.startRipple()
                }
            }
        }
    }

    private fun dismissOverlay(onComplete: () -> Unit) {
        if (voiceKeyboardSession) {
            rootLayout?.animate()
                ?.alpha(0f)
                ?.translationY(-dp(60).toFloat())
                ?.setDuration(260)
                ?.setInterpolator(AccelerateInterpolator())
                ?.withEndAction { onComplete() }
                ?.start() ?: onComplete()
        } else {
            waveformView?.collapse {
                rootLayout?.alpha = 0f
                if (assistantAnimationMode == SettingsDataStore.ANIMATION_MODE_CARD) {
                    cardExpandView?.alpha = 1f
                    cardExpandView?.visibility = View.VISIBLE
                    cardExpandView?.reverse { onComplete() }
                } else if (assistantAnimationMode == SettingsDataStore.ANIMATION_MODE_TREE) {
                    treeView?.alpha = 1f
                    treeView?.visibility = View.VISIBLE
                    treeView?.reverse { onComplete() }
                } else {
                    snakeView?.alpha = 1f
                    snakeView?.visibility = View.VISIBLE
                    snakeView?.reverse { onComplete() }
                }
            } ?: onComplete()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "WhisperFlow::AssistTranscription"
            ).apply {
                // Safety timeout: auto-release after 5 minutes to prevent battery drain
                // if something goes wrong and we forget to release
                acquire(5 * 60 * 1000L)
            }
            Log.d(TAG, "🔋 Wake lock acquired (SCREEN_BRIGHT)")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "🔋 Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun startRecording() {
        val recorder = audioRecorder ?: return
        if (isRecording) return

        liveChunks.clear()
        isRecording = true

        // Keep CPU awake during recording + transcription
        acquireWakeLock()

        SoundFeedback.playStart()
        val started = recorder.start()
        if (!started) {
            isRecording = false
            releaseWakeLock()
            tvStatus?.text = "⚠ MIC PERMISSION NEEDED"
            tvStatus?.setTextColor(0xFFFF4444.toInt())
            Log.e(TAG, "Cannot start recording — open WhisperFlow app to grant mic permission")
            return
        }

        Log.d(TAG, "Recording started (stopMode=$assistantStopMode, voiceKb=$voiceKeyboardSession)")
        updateUI()
        val allowLiveChunks = liveChunkingEnabled && (inferenceMode != "LLM_GATEWAY" || voiceKeyboardSession)
        if (allowLiveChunks) {
            startLiveChunking()
        }
        startDurationUpdates()
        // Only auto-stop on silence if configured
        if (assistantStopMode == SettingsDataStore.ASSISTANT_STOP_SILENCE) {
            startSilenceDetection()
        }
        waveformView?.startRecordingAnimation(recorder)
    }

    private fun stopRecording(commitFinal: Boolean) {
        if (!isRecording) return
        val wasVoiceKb = voiceKeyboardSession
        isRecording = false
        SoundFeedback.playStop()

        chunkJob?.cancel()
        chunkJob = null
        durationJob?.cancel()
        durationJob = null
        silenceJob?.cancel()
        silenceJob = null
        waveformView?.stopAnimation()
        inferenceGraphView?.setInferenceActive(false)

        val recorder = audioRecorder ?: return
        val wavFile = recorder.stop()

        if (commitFinal && wavFile != null && liveChunks.isNotEmpty()) {
                val liveText = liveChunks.joinToString(" ").trim()

                val ctx = context
                sessionScope?.launch {
                    val settings = SettingsDataStore(ctx)
                    var finalText = liveText

                    if (ramblerEnabled && liveText.isNotBlank()) {
                        val gatewayUrl = settings.gatewayUrl.first()
                        val api = WhisperApi("")
                        val cleaned = api.cleanRamblerText(gatewayUrl, liveText)
                        if (cleaned != null && cleaned.trim() != liveText) {
                            finalText = cleaned.trim()
                        }
                    }

                    settings.updateLastTranscription(finalText)
                    val audioPath = settings.saveRecordingPermanently(wavFile)
                    val modeStr = when (inferenceMode) {
                        "LOCAL" -> "LOCAL:${settings.localModel.first()}"
                        "LLM_GATEWAY" -> "GATEWAY:whisper-large-v3-turbo"
                        else -> "GPU_SERVER"
                    }

                    var sentToTasks = false
                    if (!wasVoiceKb && settings.tasksEnabled.first() && !settings.assistantEnabled.first() && finalText.isNotBlank()) {
                        val tasksUrl = settings.tasksUrl.first()
                        val tasksApi = TasksApi(tasksUrl)
                        val taskResult = tasksApi.createTask(finalText)
                        sentToTasks = taskResult.success
                        if (sentToTasks) SoundFeedback.playTaskSaved()
                    }

                    var assistantUsed = false
                    var assistantMessage: String? = null
                    if (!wasVoiceKb && settings.assistantEnabled.first() && finalText.isNotBlank()) {
                        assistantUsed = true
                        val assistantUrl = settings.assistantUrl.first()
                        val assistantApi = WhisperHookApi(assistantUrl, "Assistant")
                        val hookResult = assistantApi.send(finalText)
                        assistantMessage = hookResult.message.ifBlank { "⚠️ Assistant unreachable" }
                    }

                    var streakMessage: String? = null
                    if (!wasVoiceKb && !settings.assistantEnabled.first() && settings.streakEnabled.first() && finalText.isNotBlank()) {
                        val streakUrl = settings.streakUrl.first()
                        val streakApi = WhisperHookApi(streakUrl, "Streak")
                        val hookResult = streakApi.send(finalText)
                        if (hookResult.success && hookResult.action != "ignored") {
                            streakMessage = hookResult.message
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (wasVoiceKb) {
                            finishVoiceKeyboardSession(finalText, wasVoiceKb = true)
                            SoundFeedback.playSuccess()
                        } else {
                            copyTranscriptionUnlessAssistantAnswered(
                                finalText,
                                assistantMessage,
                                streakMessage,
                            )
                            SoundFeedback.playSuccess()
                            val toastParts = mutableListOf<String>()
                            if (sentToTasks) toastParts.add("✅ Task")
                            if (assistantMessage != null) toastParts.add(assistantMessage)
                            if (streakMessage != null) toastParts.add(streakMessage)
                            val toastMsg = when {
                                toastParts.isNotEmpty() -> toastParts.joinToString(" • ")
                                assistantUsed -> assistantMessage ?: "⚠️ Assistant: no response"
                                else -> clipboardToast(ctx, finalText.take(70))
                            }
                            Toast.makeText(ctx, toastMsg, Toast.LENGTH_LONG).show()
                        }
                    }

                    val languageSetting = settings.language.first()
                    settings.addToHistory(finalText, recorder.durationMs.value, languageSetting, audioPath, modeStr, sentToTasks)
                }

                dismissOverlay { hide() }
            } else if (commitFinal && wavFile != null) {
                // No live chunks — process in background and dismiss immediately
                val currentEngine = localEngine
                val currentMode = inferenceMode
                val ctx = context.applicationContext
                val capturedLanguageToken = localLanguageToken

                Log.d(TAG, "📡 No live chunks — processing in background, dismissing now (voiceKb=$wasVoiceKb)")

                dismissOverlay { hide() }

                // Process in background (survives session close)
                @OptIn(DelicateCoroutinesApi::class)
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val settings = SettingsDataStore(ctx)
                        val finalText: String
                        val durationMs: Long
                        val languageSetting = settings.language.first()
                        val modeStr: String

                        if (currentMode == "LOCAL" && currentEngine != null) {
                            // Local inference
                            Log.d(TAG, "📡 Background full transcription → LOCAL engine")
                            val samples = audioFileToFloatSamples(wavFile)
                            val localResult = currentEngine.transcribe(samples, capturedLanguageToken)
                            finalText = localResult.text.trim()
                            durationMs = localResult.processingTimeMs
                            modeStr = "LOCAL:${settings.localModel.first()}"
                        } else if (currentMode == "LLM_GATEWAY") {
                            // LLM Gateway
                            val gatewayUrl = settings.gatewayUrl.first()
                            Log.d(TAG, "📡 Background full transcription → LLM Gateway $gatewayUrl")

                            val api = WhisperApi("")
                            val result = api.transcribeViaGateway(wavFile, gatewayUrl, "whisper-large-v3-turbo", languageSetting)

                            if (!result.success || result.text.isBlank()) {
                                throw Exception(result.error ?: "Gateway transcription failed")
                            }
                            finalText = result.text.trim()
                            durationMs = result.durationMs
                            modeStr = "GATEWAY:whisper-large-v3-turbo"
                        } else {
                            // GPU Server (Default)
                            val serverUrl = settings.serverUrl.first()
                            val task = settings.task.first()
                            Log.d(TAG, "📡 Background full transcription → GPU server $serverUrl")

                            val api = WhisperApi(serverUrl)
                            val result = api.transcribe(wavFile, languageSetting, task)

                            if (!result.success || result.text.isBlank()) {
                                throw Exception(result.error ?: "Transcription failed")
                            }
                            finalText = result.text.trim()
                            durationMs = result.durationMs
                            modeStr = "GPU_SERVER"
                        }

                        if (finalText.isNotBlank()) {
                            var processedText = finalText

                            if (ramblerEnabled) {
                                val gatewayUrl = settings.gatewayUrl.first()
                                val api = WhisperApi("")
                                val cleaned = api.cleanRamblerText(gatewayUrl, finalText)
                                if (cleaned != null && cleaned.trim() != finalText) {
                                    processedText = cleaned.trim()
                                }
                            }

                            settings.updateLastTranscription(processedText)
                            val audioPath = settings.saveRecordingPermanently(wavFile)

                            var sentToTasks = false
                            if (!wasVoiceKb && settings.tasksEnabled.first() && !settings.assistantEnabled.first() && processedText.isNotBlank()) {
                                val tasksUrl = settings.tasksUrl.first()
                                val tasksApi = TasksApi(tasksUrl)
                                val taskResult = tasksApi.createTask(processedText)
                                sentToTasks = taskResult.success
                                if (sentToTasks) SoundFeedback.playTaskSaved()
                            }

                            var assistantUsed = false
                            var assistantMessage: String? = null
                            if (!wasVoiceKb && settings.assistantEnabled.first() && processedText.isNotBlank()) {
                                assistantUsed = true
                                val assistantUrl = settings.assistantUrl.first()
                                val assistantApi = WhisperHookApi(assistantUrl, "Assistant")
                                val hookResult = assistantApi.send(processedText)
                                assistantMessage = hookResult.message.ifBlank { "⚠️ Assistant unreachable" }
                            }

                            var streakMessage: String? = null
                            if (!wasVoiceKb && !settings.assistantEnabled.first() && settings.streakEnabled.first() && processedText.isNotBlank()) {
                                val streakUrl = settings.streakUrl.first()
                                val streakApi = WhisperHookApi(streakUrl, "Streak")
                                val hookResult = streakApi.send(processedText)
                                if (hookResult.success && hookResult.action != "ignored") {
                                    streakMessage = hookResult.message
                                }
                            }

                            settings.addToHistory(processedText, durationMs, languageSetting, audioPath, modeStr, sentToTasks)
                            Log.d(TAG, "✅ Background transcription ($modeStr): ${processedText.take(60)}...")

                            withContext(Dispatchers.Main) {
                                if (wasVoiceKb) {
                                    finishVoiceKeyboardSession(processedText, wasVoiceKb = true)
                                    SoundFeedback.playSuccess()
                                } else {
                                    copyTranscriptionUnlessAssistantAnswered(
                                        processedText,
                                        assistantMessage,
                                        streakMessage,
                                    )
                                    SoundFeedback.playSuccess()

                                    val toastParts = mutableListOf<String>()
                                    if (sentToTasks) toastParts.add("✅ Task")
                                    if (assistantMessage != null) toastParts.add(assistantMessage)
                                    if (streakMessage != null) toastParts.add(streakMessage)
                                    val toastMsg = when {
                                        toastParts.isNotEmpty() -> toastParts.joinToString(" • ")
                                        assistantUsed -> assistantMessage ?: "⚠️ Assistant: no response"
                                        else -> clipboardToast(ctx, processedText.take(50))
                                    }
                                    Toast.makeText(ctx, toastMsg, Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                             Log.d(TAG, "📡 Background transcription failed: empty result text")
                             withContext(Dispatchers.Main) {
                                 SoundFeedback.playError()
                                 Toast.makeText(ctx, "⚠ Empty transcription", Toast.LENGTH_SHORT).show()
                             }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "📡 Background transcription error: ${e.message}")
                        withContext(Dispatchers.Main) {
                            SoundFeedback.playError()
                            Toast.makeText(ctx, "⚠ ${e.message?.take(30) ?: "Failed"}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                updateUI()
            }
    }

    private fun animatePreviewFlash() {
        val tv = tvLiveText ?: return
        val normalColor = tv.currentTextColor
        val flashColor = 0xFFFF8B00.toInt() // Amber
        val animator = ValueAnimator.ofArgb(flashColor, normalColor)
        animator.duration = 400
        animator.addUpdateListener { anim ->
            tv.setTextColor(anim.animatedValue as Int)
        }
        animator.start()
    }

    private fun applyVoiceKeyboardText(text: String, showToast: Boolean) {
        if (text.isBlank() || !FocusTextInjector.hasActiveSession()) return
        val injected = FocusTextInjector.updateDictation(text)
        if (showToast) {
            val msg = if (injected) "✅ Typed: ${text.take(60)}" else "⚠ Could not type into field"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishVoiceKeyboardSession(finalText: String, wasVoiceKb: Boolean) {
        if (!wasVoiceKb) return
        if (finalText.isNotBlank()) {
            applyVoiceKeyboardText(finalText, showToast = true)
        } else {
            Toast.makeText(context, "⚠ No speech detected", Toast.LENGTH_SHORT).show()
        }
        FocusTextInjector.endSession()
        voiceKeyboardSession = false
    }

    /** Skip clipboard when a connector answered — avoids Samsung's extra "Copied" toast. */
    private fun copyTranscriptionUnlessAssistantAnswered(
        transcription: String,
        assistantMessage: String?,
        streakMessage: String? = null,
    ) {
        val assistantAnswered = assistantMessage != null &&
            assistantMessage.isNotBlank() &&
            !assistantMessage.startsWith("⚠️")
        val streakAnswered = streakMessage != null && streakMessage.isNotBlank()
        if (assistantAnswered || streakAnswered) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(
            android.content.ClipData.newPlainText("WhisperFlow", transcription)
        )
    }

    private fun startLiveChunking() {
        chunkJob = sessionScope?.launch {
            Log.d(TAG, "📡 Live chunking started — initial delay ${CHUNK_INITIAL_DELAY_MS}ms (mode=$inferenceMode)")
            delay(CHUNK_INITIAL_DELAY_MS)
            Log.d(TAG, "📡 Live chunk loop entering — isActive=$isActive isRecording=$isRecording")
            var chunkIndex = 0
            while (isActive && isRecording) {
                val chunkResult = audioRecorder?.saveChunk()
                if (chunkResult != null) {
                    val idx = chunkIndex++
                    Log.d(TAG, "📡 Chunk #$idx saved: ${chunkResult.file.length()} bytes, peak=${chunkResult.peakAmplitude}")
                    val peak = chunkResult.peakAmplitude
                    val currentEngine = localEngine
                    val currentMode = inferenceMode

                    launch(Dispatchers.IO) {
                        try {
                            withContext(Dispatchers.Main) { inferenceGraphView?.setInferenceActive(true) }
                            val sendStart = System.currentTimeMillis()
                            val text: String?

                            if (currentMode == "LOCAL" && currentEngine != null) {
                                Log.d(TAG, "📡 Chunk #$idx → LOCAL engine")
                                val samples = audioFileToFloatSamples(chunkResult.file)
                                val localResult = currentEngine.transcribe(samples, localLanguageToken)
                                text = WhisperApi.filterHallucinations(localResult.text)
                            } else if (currentMode == "LLM_GATEWAY") {
                                val settings = SettingsDataStore(context)
                                val gatewayUrl = settings.gatewayUrl.first()
                                val language = settings.language.first()
                                text = if (peak > 0.02f) {
                                    Log.d(TAG, "📡 Chunk #$idx → LLM Gateway $gatewayUrl")
                                    val api = WhisperApi("")
                                    val result = api.transcribeViaGateway(
                                        chunkResult.file, gatewayUrl, "whisper-large-v3-turbo", language
                                    )
                                    result.text.takeIf { it.isNotBlank() }
                                } else {
                                    null
                                }
                            } else {
                                val settings = SettingsDataStore(context)
                                val serverUrl = settings.serverUrl.first()
                                val language = settings.language.first()
                                val task = settings.task.first()
                                Log.d(TAG, "📡 Chunk #$idx → GPU server $serverUrl")
                                val api = WhisperApi(serverUrl)
                                text = api.transcribeChunk(chunkResult.file, language, task, peak)
                            }

                            val elapsed = System.currentTimeMillis() - sendStart
                            chunkResult.file.delete()

                            if (!text.isNullOrBlank()) {
                                val rawChunk = text.trim()
                                withContext(Dispatchers.Main) {
                                    liveChunks.add(rawChunk)
                                    val preview = liveChunks.joinToString(" ")
                                    tvLiveText?.text = preview
                                    tvLiveText?.visibility = View.VISIBLE
                                    Log.d(TAG, "📡 Chunk #$idx ← result in ${elapsed}ms: \"${rawChunk.take(50)}\"")
                                    if (voiceKeyboardSession) {
                                        applyVoiceKeyboardText(preview, showToast = false)
                                    }
                                }

                                if (ramblerEnabled) {
                                    val settings = SettingsDataStore(context)
                                    val gatewayUrl = settings.gatewayUrl.first()
                                    val api = WhisperApi("")
                                    val joined = liveChunks.joinToString(" ")
                                    val cleaned = api.cleanRamblerText(gatewayUrl, joined)
                                    if (cleaned != null && cleaned.trim() != joined) {
                                        val cleanedText = cleaned.trim()
                                        withContext(Dispatchers.Main) {
                                            liveChunks.clear()
                                            if (cleanedText.isNotBlank()) {
                                                liveChunks.add(cleanedText)
                                            }
                                            tvLiveText?.text = cleanedText
                                            animatePreviewFlash()
                                            if (voiceKeyboardSession) {
                                                applyVoiceKeyboardText(cleanedText, showToast = false)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Log.d(TAG, "📡 Chunk #$idx ← empty/null result in ${elapsed}ms (filtered or silent)")
                            }
                            withContext(Dispatchers.Main) { inferenceGraphView?.setInferenceActive(false) }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { inferenceGraphView?.setInferenceActive(false) }
                            Log.w(TAG, "📡 Chunk #$idx ← FAILED: ${e.message}")
                            chunkResult.file.delete()
                        }
                    }
                } else {
                    Log.d(TAG, "📡 No chunk data available (recorder may have stopped)")
                }
                delay(chunkIntervalMs)
            }
            Log.d(TAG, "📡 Live chunk loop exited — isActive=$isActive isRecording=$isRecording")
        }
    }

    private fun startSilenceDetection() {
        silenceJob = sessionScope?.launch {
            val recorder = audioRecorder ?: return@launch
            var speechDetected = false
            var silenceStart = 0L

            // Give user time to start speaking before monitoring silence
            delay(MIN_RECORD_BEFORE_SILENCE)

            while (isActive && isRecording) {
                val amp = recorder.amplitude.value

                if (amp >= SPEECH_THRESHOLD) {
                    speechDetected = true
                    silenceStart = 0L
                } else if (speechDetected && amp < SILENCE_THRESHOLD) {
                    if (silenceStart == 0L) {
                        silenceStart = System.currentTimeMillis()
                        Log.d(TAG, "🔇 Silence detected (amp=${String.format("%.3f", amp)})")
                    } else {
                        val elapsed = System.currentTimeMillis() - silenceStart
                        if (elapsed >= silenceTimeoutMs) {
                            Log.d(TAG, "🔇 Silence held for ${elapsed}ms — auto-stopping")
                            stopRecording(commitFinal = true)
                            return@launch
                        }
                    }
                }
                // When amp is between thresholds (low noise), do nothing —
                // preserve silence timer state so it doesn't keep resetting

                delay(100)
            }
        }
    }

    private fun startDurationUpdates() {
        durationJob = sessionScope?.launch {
            while (isActive && isRecording) {
                val ms = audioRecorder?.durationMs?.value ?: 0L
                val formatted = AudioRecorder.formatDuration(ms)
                tvDuration?.text = formatted
                if (voiceKeyboardSession) {
                    tvDuration?.visibility = View.VISIBLE
                }
                delay(100)
            }
        }
    }

    private fun dismissAssist() {
        if (isRecording) {
            // User tapped off = they're done — commit what we have
            stopRecording(commitFinal = true)
            // stopRecording handles hide() via animation
        } else {
            dismissOverlay { hide() }
        }
    }

    private fun updateUI() {
        mainHandler.post {
            when {
                isRecording -> {
                    // Minimalist: only the waveform speaks
                }
                isProcessing -> {
                    findProcessingContainer()?.visibility = View.VISIBLE
                }
                else -> {
                    findProcessingContainer()?.visibility = View.GONE
                }
            }
        }
    }

    private fun findProcessingContainer(): View? {
        val root = rootLayout ?: return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is LinearLayout && child != rootLayout) {
                // Check if it contains a ProgressBar
                for (j in 0 until child.childCount) {
                    if (child.getChildAt(j) is ProgressBar) {
                        return child
                    }
                }
            }
        }
        return null
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}

/**
 * Convert a WAV audio file to float samples for local inference.
 */
private fun audioFileToFloatSamples(file: java.io.File): FloatArray {
    val bytes = file.readBytes()
    val buffer = java.nio.ByteBuffer.wrap(bytes)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)

    // Skip WAV header (44 bytes)
    if (bytes.size <= 44) return FloatArray(0)
    buffer.position(44)

    val numSamples = (bytes.size - 44) / 2 // 16-bit samples
    val samples = FloatArray(numSamples)

    for (i in 0 until numSamples) {
        if (buffer.remaining() >= 2) {
            val pcmValue = buffer.short
            samples[i] = pcmValue / 32768f
        }
    }

    return samples
}

// ═══════════════════════════════════════════════════════════════
// 📊 WAVEFORM VIEW — premium live amplitude visualization
//    Gradient bars, glow halos, mirrored reflections, cubic
//    bezier contour lines, spring physics, amber/noir palette
// ═══════════════════════════════════════════════════════════════

class AssistWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Configuration ────────────────────────────────────────────
    private val barCount = 50

    // ── Amber/Noir Color Palette (Vibrant) ─────────────────────────
    private val amberTop = 0xFFFFE082.toInt()      // bright warm gold top
    private val amberBottom = 0xFFFF9100.toInt()    // saturated amber bottom
    private val copperTop = 0xFFFFC400.toInt()      // vivid gold top
    private val copperBottom = 0xFFFF6D00.toInt()    // deep orange bottom
    private val hotTop = 0xFFFFF176.toInt()         // electric yellow top
    private val hotBottom = 0xFFFF3D00.toInt()      // blazing red-orange bottom
    private val dimTop = 0xFF8B5E00.toInt()         // warm dim amber top
    private val dimBottom = 0xFF4E2800.toInt()      // deep brown bottom
    private val idleColor = 0x55FF9100.toInt()     // visible amber ghost
    private val glowAmber = 0x77FF9100.toInt()      // bold amber glow
    private val glowHot = 0x88FFD54F.toInt()        // bright hot glow
    private val contourColor = 0x66FFC400.toInt()   // vivid contour line

    // ── State ────────────────────────────────────────────────────
    private val amplitudeHistory = FloatArray(barCount)
    private var historyIndex = 0
    private var isAnimating = false
    private var recorder: AudioRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private val smoothAmplitudes = FloatArray(barCount)
    private val barVelocities = FloatArray(barCount)  // spring physics
    private var idlePhase = 0f

    // ── Paint Objects ────────────────────────────────────────────
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val reflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val contourPath = Path()
    private val barRect = RectF()

    // Spring physics constants
    private val springStiffness = 0.12f
    private val springDamping = 0.72f

    // Cached gradients
    private var gradientAmber: android.graphics.LinearGradient? = null
    private var gradientCopper: android.graphics.LinearGradient? = null
    private var gradientHot: android.graphics.LinearGradient? = null
    private var gradientDim: android.graphics.LinearGradient? = null
    private var gradientReflection: android.graphics.LinearGradient? = null
    private var lastCacheH = 0f
    private var lastCacheCenterY = 0f

    init {
        smoothAmplitudes.fill(0f)
        amplitudeHistory.fill(0f)
        barVelocities.fill(0f)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isAnimating) {
                val amp = recorder?.amplitude?.value ?: 0f
                amplitudeHistory[historyIndex % barCount] = amp
                historyIndex++

                // Spring-based smoothing for organic bounce
                for (i in 0 until barCount) {
                    val idx = if (historyIndex >= barCount) {
                        (historyIndex - barCount + i) % barCount
                    } else {
                        if (i < historyIndex) i else -1
                    }
                    val raw = if (idx >= 0) amplitudeHistory[idx] else 0f
                    val target = (raw * 1.2f).coerceIn(0.03f, 0.85f)
                    val delta = target - smoothAmplitudes[i]
                    barVelocities[i] = barVelocities[i] * springDamping + delta * springStiffness
                    smoothAmplitudes[i] = (smoothAmplitudes[i] + barVelocities[i]).coerceIn(0.02f, 1f)
                }

                invalidate()
                handler.postDelayed(this, 32) // ~60fps
            } else {
                idlePhase += 0.04f
                // Gentle idle spring update
                for (i in 0 until barCount) {
                    val phase = idlePhase + (i.toFloat() / barCount) * 4f
                    val target = (kotlin.math.sin(phase.toDouble()).toFloat() * 0.10f + 0.10f).coerceIn(0.04f, 0.22f)
                    //val target = (kotlin.math.sin(phase.toDouble()).toFloat() * 0.06f + 0.08f).coerceIn(0.04f, 0.16f)
                    smoothAmplitudes[i] += (target - smoothAmplitudes[i]) * 0.18f
                }
                invalidate()
                handler.postDelayed(this, 33)
            }
        }
    }

    init {
        handler.post(updateRunnable)
    }

    /**
     * Emerge animation: bars grow from flat line to idle breathing state.
     * Called immediately after the snake/tree line-stretch completes so the
     * transition from stretched line → waveform bars is a single visual motion.
     */
    fun emerge() {
        smoothAmplitudes.fill(0f)
        barVelocities.fill(0f)
        idlePhase = 0f
        // Kick the idle loop — it will naturally grow bars from 0 via spring physics.
        // We give it extra initial velocity for a snappy emergence.
        for (i in 0 until barCount) {
            val phase = (i.toFloat() / barCount) * 4f
            val target = (kotlin.math.sin(phase.toDouble()).toFloat() * 0.10f + 0.10f).coerceIn(0.04f, 0.22f)
            barVelocities[i] = target * 0.4f  // initial upward kick
        }
    }

    fun startRecordingAnimation(audioRecorder: AudioRecorder) {
        recorder = audioRecorder
        isAnimating = true
        amplitudeHistory.fill(0f)
        smoothAmplitudes.fill(0f)
        barVelocities.fill(0f)
        historyIndex = 0
    }

    fun stopAnimation() {
        isAnimating = false
        recorder = null
        barVelocities.fill(0f)
    }

    // ── Rendering ────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = w / barCount
        val gap = barWidth * 0.22f
        val effectiveWidth = barWidth - gap
        val centerY = h * 0.45f  // slightly above center for reflection space
        val cornerRadius = effectiveWidth / 2f
        val maxBarH = h * 0.75f

        // Rebuild gradient caches if dimensions changed
        if (lastCacheH != h || lastCacheCenterY != centerY) {
            lastCacheH = h
            lastCacheCenterY = centerY
            rebuildGradientCaches(centerY, maxBarH)
        }

        // ── Glow layer (behind bars) ─────────────────────────────
        if (isAnimating && historyIndex > 0) {
            val blurRadius = effectiveWidth * 2.5f
            glowPaint.maskFilter = android.graphics.BlurMaskFilter(blurRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
            for (i in 0 until barCount) {
                val amp = smoothAmplitudes[i]
                if (amp < 0.12f) continue
                val barH = amp * maxBarH
                val x = i * barWidth + gap / 2f
                barRect.set(
                    x - effectiveWidth * 0.3f,
                    centerY - barH / 2f,
                    x + effectiveWidth * 1.3f,
                    centerY + barH / 2f
                )
                glowPaint.color = if (amp > 0.5f) glowHot else glowAmber
                canvas.drawRoundRect(barRect, cornerRadius * 2f, cornerRadius * 2f, glowPaint)
            }
        }

        // ── Main gradient bars ───────────────────────────────────
        for (i in 0 until barCount) {
            val amp = smoothAmplitudes[i].coerceIn(0.02f, 1f)
            val barH = amp * maxBarH
            val x = i * barWidth + gap / 2f
            val top = centerY - barH / 2f
            val bottom = centerY + barH / 2f
            barRect.set(x, top, x + effectiveWidth, bottom)

            if (isAnimating && historyIndex > 0) {
                // Pick gradient by amplitude tier
                barPaint.shader = when {
                    amp > 0.5f -> gradientHot
                    amp > 0.3f -> gradientCopper
                    amp > 0.12f -> gradientAmber
                    else -> gradientDim
                }
                barPaint.clearShadowLayer()
            } else {
                barPaint.shader = null
                barPaint.color = idleColor
                barPaint.clearShadowLayer()
            }

            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
        }
        barPaint.shader = null

        // ── Mirrored reflection ──────────────────────────────────
        if (isAnimating && historyIndex > 0) {
            val reflectionOffset = 2f * resources.displayMetrics.density
            reflectionPaint.shader = gradientReflection
            for (i in 0 until barCount) {
                val amp = smoothAmplitudes[i]
                if (amp < 0.08f) continue
                val barH = amp * maxBarH
                val x = i * barWidth + gap / 2f
                val reflH = barH * 0.30f
                barRect.set(
                    x,
                    centerY + barH / 2f + reflectionOffset,
                    x + effectiveWidth,
                    centerY + barH / 2f + reflectionOffset + reflH
                )
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, reflectionPaint)
            }
        }

        // ── Smooth contour line (connecting bar tops) ────────────
        if (isAnimating && historyIndex > 0) {
            drawContour(canvas, barWidth, gap, effectiveWidth, centerY, maxBarH)
        }
    }

    private fun drawContour(
        canvas: Canvas,
        barWidth: Float,
        gap: Float,
        effectiveWidth: Float,
        centerY: Float,
        maxBarH: Float
    ) {
        contourPaint.color = contourColor
        contourPaint.strokeWidth = resources.displayMetrics.density * 1.2f

        // Top contour
        contourPath.reset()
        val firstX = gap / 2f + effectiveWidth / 2f
        val firstH = smoothAmplitudes[0] * maxBarH / 2f
        contourPath.moveTo(firstX, centerY - firstH)

        for (i in 1 until barCount) {
            val prevX = (i - 1) * barWidth + gap / 2f + effectiveWidth / 2f
            val prevH = smoothAmplitudes[i - 1] * maxBarH / 2f
            val currX = i * barWidth + gap / 2f + effectiveWidth / 2f
            val currH = smoothAmplitudes[i] * maxBarH / 2f
            val cpx = (prevX + currX) / 2f
            contourPath.cubicTo(cpx, centerY - prevH, cpx, centerY - currH, currX, centerY - currH)
        }
        canvas.drawPath(contourPath, contourPaint)

        // Bottom contour (mirrored, dimmer)
        contourPath.reset()
        contourPaint.color = (contourColor and 0x00FFFFFF) or 0x22000000
        contourPath.moveTo(firstX, centerY + firstH)
        for (i in 1 until barCount) {
            val prevX = (i - 1) * barWidth + gap / 2f + effectiveWidth / 2f
            val prevH = smoothAmplitudes[i - 1] * maxBarH / 2f
            val currX = i * barWidth + gap / 2f + effectiveWidth / 2f
            val currH = smoothAmplitudes[i] * maxBarH / 2f
            val cpx = (prevX + currX) / 2f
            contourPath.cubicTo(cpx, centerY + prevH, cpx, centerY + currH, currX, centerY + currH)
        }
        canvas.drawPath(contourPath, contourPaint)
    }

    // ── Gradient Cache ───────────────────────────────────────────
    private fun rebuildGradientCaches(centerY: Float, maxBarH: Float) {
        val topY = centerY - maxBarH / 2f
        val bottomY = centerY + maxBarH / 2f

        gradientAmber = android.graphics.LinearGradient(
            0f, topY, 0f, bottomY, amberTop, amberBottom,
            android.graphics.Shader.TileMode.CLAMP
        )
        gradientCopper = android.graphics.LinearGradient(
            0f, topY, 0f, bottomY, copperTop, copperBottom,
            android.graphics.Shader.TileMode.CLAMP
        )
        gradientHot = android.graphics.LinearGradient(
            0f, topY, 0f, bottomY, hotTop, hotBottom,
            android.graphics.Shader.TileMode.CLAMP
        )
        gradientDim = android.graphics.LinearGradient(
            0f, topY, 0f, bottomY, dimTop, dimBottom,
            android.graphics.Shader.TileMode.CLAMP
        )

        val reflTopY = centerY + maxBarH * 0.38f
        val reflBotY = centerY + maxBarH * 0.65f
        gradientReflection = android.graphics.LinearGradient(
            0f, reflTopY, 0f, reflBotY,
            0x40FF8B00, 0x00FF8B00,
            android.graphics.Shader.TileMode.CLAMP
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(updateRunnable)
        isAnimating = false
    }

    fun collapse(onComplete: () -> Unit) {
        isAnimating = false
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 280
            interpolator = AccelerateInterpolator(1.5f)
            addUpdateListener {
                val v = it.animatedValue as Float
                for (i in smoothAmplitudes.indices) {
                    // Outside-in collapse: edges shrink faster
                    val distFromCenter = Math.abs(i - barCount / 2f) / (barCount / 2f)
                    val factor = v * (1f - distFromCenter * (1f - v) * 0.5f)
                    smoothAmplitudes[i] = smoothAmplitudes[i] * factor.coerceIn(0f, 1f)
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onComplete()
                }
            })
            start()
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 📊 INFERENCE GRAPH VIEW — real-time bar graph of inference activity
// ═══════════════════════════════════════════════════════════════

class AssistInferenceGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFF3333.toInt()
        textSize = 9f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FF3333.toInt()
        textSize = 8f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18FFFFFF.toInt()
        strokeWidth = 1f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val historySize = 30
    private val activityHistory = FloatArray(historySize) { 0.03f + (Math.random() * 0.05f).toFloat() }
    private var historyIndex = 0

    private var isInferenceActive = false
    private val handler = Handler(Looper.getMainLooper())

    private val graphRedHigh = 0xDDFF2222.toInt()
    private val graphRedMid = 0xAACC1111.toInt()
    private val graphRedLow = 0x66881111.toInt()
    private val graphRedDim = 0x33661111.toInt()

    private val updateRunnable = object : Runnable {
        override fun run() {
            val newValue = if (isInferenceActive) {
                0.5f + (Math.random() * 0.45f).toFloat()
            } else {
                0.03f + (Math.random() * 0.07f).toFloat()
            }
            activityHistory[historyIndex % historySize] = newValue
            historyIndex++
            invalidate()
            handler.postDelayed(this, 100)
        }
    }

    init {
        handler.post(updateRunnable)
    }

    fun setInferenceActive(active: Boolean) {
        isInferenceActive = active
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val dp = resources.displayMetrics.density
        val headerHeight = 20f * dp
        val graphTop = headerHeight + 4f * dp
        val graphHeight = h - graphTop - 4f * dp
        val barWidth = w / historySize

        // Draw dot indicator
        dotPaint.color = if (isInferenceActive) 0xFFFF3333.toInt() else 0x88FF3333.toInt()
        canvas.drawCircle(4f * dp, headerHeight / 2f, 3f * dp, dotPaint)

        // Draw header label
        val label = if (isInferenceActive) "INFERENCE ACTIVE" else "ADRENO GPU"
        textPaint.color = if (isInferenceActive) 0xFFFF3333.toInt() else 0x88FF3333.toInt()
        canvas.drawText(label, 12f * dp, headerHeight / 2f + textPaint.textSize / 3f, textPaint)

        // Draw percentage
        val lastIdx = if (historyIndex > 0) ((historyIndex - 1) % historySize) else 0
        val pct = "${(activityHistory[lastIdx] * 100).toInt()}%"
        val pctWidth = labelPaint.measureText(pct)
        labelPaint.color = if (isInferenceActive) 0xFFFF4444.toInt() else 0x88FF3333.toInt()
        canvas.drawText(pct, w - pctWidth - 4f * dp, headerHeight / 2f + labelPaint.textSize / 3f, labelPaint)

        // Draw grid lines
        for (i in 1..3) {
            val y = graphTop + graphHeight * (1f - i / 4f)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // Draw bars
        for (i in 0 until historySize) {
            val idx = if (historyIndex >= historySize) {
                (historyIndex - historySize + i) % historySize
            } else {
                if (i < historyIndex) i else 0
            }
            val value = activityHistory[idx]
            val barH = value * graphHeight
            val x = i * barWidth
            val alpha = 0.3f + (i.toFloat() / historySize) * 0.7f

            barPaint.color = when {
                value > 0.7f -> graphRedHigh
                value > 0.4f -> graphRedMid
                value > 0.15f -> graphRedLow
                else -> graphRedDim
            }
            // Apply fade-in alpha for older bars
            barPaint.alpha = (barPaint.alpha * alpha).toInt()

            canvas.drawRect(
                x + 1f,
                graphTop + graphHeight - barH,
                x + barWidth - 1f,
                graphTop + graphHeight,
                barPaint
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(updateRunnable)
    }

}


// ═══════════════════════════════════════════════════════════════
// 🌊 RIPPLE VIEW — background expands from power button
// ═══════════════════════════════════════════════════════════════

class AssistRippleView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val ripples = mutableListOf<Ripple>()
    private val handler = Handler(Looper.getMainLooper())

    private class Ripple(var radius: Float, var alpha: Int)

    private val rippleRunnable = object : Runnable {
        override fun run() {
            val iterator = ripples.iterator()
            while (iterator.hasNext()) {
                val r = iterator.next()
                r.radius += 12f
                r.alpha -= 4
                if (r.alpha <= 0) iterator.remove()
            }
            invalidate()
            if (ripples.isNotEmpty()) {
                handler.postDelayed(this, 32)
            }
        }
    }

    fun startRipple() {
        ripples.clear()
        ripples.add(Ripple(0f, 180))
        handler.postDelayed({ ripples.add(Ripple(0f, 120)) }, 250)
        handler.postDelayed({ ripples.add(Ripple(0f, 80)) }, 500)
        handler.removeCallbacks(rippleRunnable)
        handler.post(rippleRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Center of ripple at the power button (right side, ~30% from top)
        val centerX = w
        val centerY = h * 0.3f

        for (r in ripples) {
            paint.color = 0xFFFF8B00.toInt()
            paint.alpha = r.alpha
            canvas.drawCircle(centerX, centerY, r.radius, paint)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 🐍 SNAKE VIEW — travels from power button to bottom
// ═══════════════════════════════════════════════════════════════

class SnakeView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density

    // Match AssistWaveformView palette exactly
    private val amberGold = 0xFFFFE082.toInt()    // bright warm gold (waveform amberTop)
    private val amberCore = 0xFFFF9100.toInt()    // saturated amber (waveform amberBottom)
    private val copperGold = 0xFFFFC400.toInt()   // vivid gold (waveform copperTop)
    private val glowColor = 0x77FF9100.toInt()    // bold amber glow (waveform glowAmber)
    private val hotGlow = 0x88FFD54F.toInt()      // bright hot glow (waveform glowHot)
    private val contourColor = 0x66FFC400.toInt() // vivid contour (waveform contourColor)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        strokeWidth = 10f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = android.graphics.BlurMaskFilter(8f * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val hotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hotGlow
        strokeWidth = 14f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = android.graphics.BlurMaskFilter(12f * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val lineGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        strokeWidth = 10f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = android.graphics.BlurMaskFilter(8f * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val contourLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = contourColor
        strokeWidth = 1.2f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private var progress = 0f
    private var lineStretch = 0f  // 0 = point, 1 = full waveform width
    private var isReversing = false
    private var targetY = 0f
    var animationSpeed = 1.0f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun start(targetCenterY: Float, onComplete: () -> Unit) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) {
            post { start(targetCenterY, onComplete) }
            return
        }

        this.targetY = targetCenterY
        isReversing = false
        lineStretch = 0f
        visibility = View.VISIBLE
        path.reset()
        val startX = w
        val startY = h * 0.3f
        val destX = w / 2f

        path.moveTo(startX, startY)
        path.cubicTo(w * 0.9f, h * 0.4f, w * 0.1f, h * 0.6f, destX, targetY)

        alpha = 1f
        // Phase 1: Snake travels the path
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (480 / animationSpeed).toLong()
            interpolator = DecelerateInterpolator(2.0f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Phase 2: Line stretches horizontally from center
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = (200 / animationSpeed).toLong()
                        interpolator = DecelerateInterpolator(1.5f)
                        addUpdateListener {
                            lineStretch = it.animatedValue as Float
                            invalidate()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                onComplete()
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    fun reverse(onComplete: () -> Unit) {
        isReversing = true
        visibility = View.VISIBLE
        // Phase 1: Line collapses to center point
        ValueAnimator.ofFloat(lineStretch, 0f).apply {
            duration = (150 / animationSpeed).toLong()
            interpolator = AccelerateInterpolator(1.5f)
            addUpdateListener {
                lineStretch = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Phase 2: Snake travels back
                    ValueAnimator.ofFloat(1f, 0f).apply {
                        duration = (380 / animationSpeed).toLong()
                        interpolator = AccelerateInterpolator(1.6f)
                        addUpdateListener {
                            progress = it.animatedValue as Float
                            invalidate()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                onComplete()
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    private var snakeGradient: android.graphics.LinearGradient? = null
    private var lineGradient: android.graphics.LinearGradient? = null

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f && lineStretch <= 0f) return

        val w = width.toFloat()
        val centerX = w / 2f

        // Draw the stretched line (visible during stretch phase)
        if (lineStretch > 0f) {
            val totalWidth = w * 0.85f
            val halfLen = (totalWidth / 2f) * lineStretch
            val left = centerX - halfLen
            val right = centerX + halfLen

            // Rebuild line gradient if needed
            if (lineGradient == null || halfLen > 0f) {
                lineGradient = android.graphics.LinearGradient(
                    left, targetY, right, targetY,
                    amberGold, copperGold,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            linePaint.shader = lineGradient

            // Layered glow → contour → main line (matches waveform rendering order)
            canvas.drawLine(left, targetY, right, targetY, lineGlowPaint)
            canvas.drawLine(left, targetY, right, targetY, contourLinePaint)
            canvas.drawLine(left, targetY, right, targetY, linePaint)
            linePaint.shader = null
        }

        // Draw snake path (fades out as line stretches)
        if (progress > 0f) {
            val measure = PathMeasure(path, false)
            val length = measure.length
            val partialPath = Path()
            val tailLen = 0.25f
            val start = (length * (progress - tailLen)).coerceAtLeast(0f)
            val end = length * progress
            measure.getSegment(start, end, partialPath, true)

            // Apply gradient along the path direction (gold → amber like waveform)
            if (snakeGradient == null) {
                snakeGradient = android.graphics.LinearGradient(
                    w, 0f, centerX, targetY,
                    amberGold, amberCore,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            paint.shader = snakeGradient

            val fadeAlpha = ((1f - lineStretch * 2f).coerceIn(0f, 1f) * 255).toInt()
            hotGlowPaint.alpha = (fadeAlpha * 0.2f).toInt()
            glowPaint.alpha = (fadeAlpha * 0.47f).toInt()
            paint.alpha = fadeAlpha

            // Render order: hot outer glow → inner glow → crisp gradient stroke
            canvas.drawPath(partialPath, hotGlowPaint)
            canvas.drawPath(partialPath, glowPaint)
            canvas.drawPath(partialPath, paint)

            paint.shader = null
            paint.alpha = 255
            glowPaint.alpha = (0x77)
            hotGlowPaint.alpha = (0x88)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 🌳 TREE VIEW — organic branches growing from power button
// ═══════════════════════════════════════════════════════════════

class TreeView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density

    // Match AssistWaveformView palette exactly
    private val amberGold = 0xFFFFE082.toInt()    // bright warm gold (waveform amberTop)
    private val amberCore = 0xFFFF9100.toInt()    // saturated amber (waveform amberBottom)
    private val copperGold = 0xFFFFC400.toInt()   // vivid gold (waveform copperTop)
    private val glowColor = 0x77FF9100.toInt()    // bold amber glow (waveform glowAmber)
    private val hotGlow = 0x88FFD54F.toInt()      // bright hot glow (waveform glowHot)
    private val contourColor = 0x66FFC400.toInt() // vivid contour (waveform contourColor)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        strokeWidth = 9f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = android.graphics.BlurMaskFilter(7f * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val hotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hotGlow
        strokeWidth = 13f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = android.graphics.BlurMaskFilter(10f * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val lineGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        strokeWidth = 10f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = android.graphics.BlurMaskFilter(8f * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val contourLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = contourColor
        strokeWidth = 1.2f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val branches = mutableListOf<Branch>()
    private var progress = 0f
    private var lineStretch = 0f
    private var targetY = 0f
    private var isReversing = false
    var animationSpeed = 1.0f
    private var branchGradient: android.graphics.LinearGradient? = null
    private var lineGradient: android.graphics.LinearGradient? = null

    private class Branch(val path: Path, val isMain: Boolean = false)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun start(targetCenterY: Float, onComplete: () -> Unit) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) {
            post { start(targetCenterY, onComplete) }
            return
        }

        this.targetY = targetCenterY
        isReversing = false
        lineStretch = 0f
        visibility = View.VISIBLE
        branches.clear()

        val startX = w
        val startY = h * 0.3f
        val destX = w / 2f

        // Main branch
        val mainPath = Path().apply {
            moveTo(startX, startY)
            cubicTo(w * 0.8f, h * 0.4f, w * 0.3f, h * 0.7f, destX, targetY)
        }
        branches.add(Branch(mainPath, true))

        // Side branches for organic feel
        branches.add(Branch(Path().apply {
            moveTo(startX, startY)
            quadTo(w * 0.7f, h * 0.2f, w * 0.4f, h * 0.15f)
        }))
        branches.add(Branch(Path().apply {
            moveTo(startX, startY)
            quadTo(w * 0.8f, h * 0.5f, w * 0.6f, h * 0.65f)
        }))
        branches.add(Branch(Path().apply {
            moveTo(startX, startY)
            quadTo(w * 0.6f, h * 0.45f, w * 0.3f, h * 0.4f)
        }))

        alpha = 1f
        // Phase 1: Branches grow
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (550 / animationSpeed).toLong()
            interpolator = DecelerateInterpolator(2.0f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Phase 2: Line stretches at target
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = (200 / animationSpeed).toLong()
                        interpolator = DecelerateInterpolator(1.5f)
                        addUpdateListener {
                            lineStretch = it.animatedValue as Float
                            invalidate()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                onComplete()
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    fun reverse(onComplete: () -> Unit) {
        isReversing = true
        visibility = View.VISIBLE
        // Phase 1: Line collapses
        ValueAnimator.ofFloat(lineStretch, 0f).apply {
            duration = (150 / animationSpeed).toLong()
            interpolator = AccelerateInterpolator(1.5f)
            addUpdateListener {
                lineStretch = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Phase 2: Branches retract
                    ValueAnimator.ofFloat(1f, 0f).apply {
                        duration = (400 / animationSpeed).toLong()
                        interpolator = AccelerateInterpolator(1.6f)
                        addUpdateListener {
                            progress = it.animatedValue as Float
                            invalidate()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                onComplete()
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f && lineStretch <= 0f) return

        val w = width.toFloat()
        val centerX = w / 2f

        // Draw the stretched line (matches waveform center line aesthetic)
        if (lineStretch > 0f) {
            val totalWidth = w * 0.85f
            val halfLen = (totalWidth / 2f) * lineStretch
            val left = centerX - halfLen
            val right = centerX + halfLen

            if (lineGradient == null || halfLen > 0f) {
                lineGradient = android.graphics.LinearGradient(
                    left, targetY, right, targetY,
                    amberGold, copperGold,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            linePaint.shader = lineGradient

            canvas.drawLine(left, targetY, right, targetY, lineGlowPaint)
            canvas.drawLine(left, targetY, right, targetY, contourLinePaint)
            canvas.drawLine(left, targetY, right, targetY, linePaint)
            linePaint.shader = null
        }

        // Draw branches (fade as line stretches)
        if (progress > 0f) {
            val branchFade = (1f - lineStretch * 1.5f).coerceIn(0f, 1f)

            // Build gradient for branches (gold → amber)
            if (branchGradient == null) {
                branchGradient = android.graphics.LinearGradient(
                    w, 0f, centerX, targetY,
                    amberGold, amberCore,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            paint.shader = branchGradient

            for (branch in branches) {
                val measure = PathMeasure(branch.path, false)
                val length = measure.length
                val partialPath = Path()

                val tailLen = if (branch.isMain) 0.3f else 0.5f
                val end = length * progress
                val start = if (branch.isMain) {
                    (end - length * tailLen).coerceAtLeast(0f)
                } else {
                    0f
                }
                measure.getSegment(start, end, partialPath, true)

                val baseAlpha = if (branch.isMain) 255 else (180 * (1f - progress * 0.3f)).toInt()
                val finalAlpha = (baseAlpha * branchFade).toInt()
                paint.alpha = finalAlpha.coerceIn(0, 255)
                glowPaint.alpha = (finalAlpha * 0.47f).toInt().coerceIn(0, 255)
                hotGlowPaint.alpha = (finalAlpha * 0.2f).toInt().coerceIn(0, 255)

                // Render: hot glow → inner glow → gradient stroke
                if (branch.isMain) {
                    canvas.drawPath(partialPath, hotGlowPaint)
                    canvas.drawPath(partialPath, glowPaint)
                }
                canvas.drawPath(partialPath, paint)
            }
            paint.shader = null
            paint.alpha = 255
            glowPaint.alpha = 0x77
            hotGlowPaint.alpha = 0x88
        }
    }
}
