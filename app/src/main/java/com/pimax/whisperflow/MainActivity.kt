package com.pimax.whisperflow

import android.content.Intent
import com.pimax.whisperflow.sync.uiText
import android.Manifest
import com.pimax.whisperflow.local.ModelManager
import com.pimax.whisperflow.local.TranscriptionEngine
import com.pimax.whisperflow.server.WhisperServer
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.util.Log
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import android.app.ActivityManager
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════════════════════════════
// 🎨 DESIGN TOKENS — Noir Studio / Electric Amber
// ═══════════════════════════════════════════════════════════════

private val Amber = Color(0xFFFF8B00)
private val AmberLight = Color(0xFFFFAD42)
private val AmberDim = Color(0xFF7A4500)
private val Copper = Color(0xFFE85D26)
private val DeepNoir = Color(0xFF0D0D12)
private val DarkSlate = Color(0xFF16161E)
private val CardSurface = Color(0xFF1C1C28)
private val WarmWhite = Color(0xFFF5F0EB)
private val WarmGray = Color(0xFF7A7580)
private val MutedGray = Color(0xFF4A4654)
private val Emerald = Color(0xFF00C77B)
private val CoralRed = Color(0xFFFF4444)
private val SkyBlue = Color(0xFF4FC3F7)

private const val TAG = "🎙️ WhisperFlow"

/** Derive pimax-auth URL from any service URL on the same Pi host (port 8100). */
fun authUrlFromTasksUrl(serviceUrl: String): String {
    return try {
        val uri = java.net.URI(serviceUrl)
        if (uri.host != null) "http://${uri.host}:8100" else ""
    } catch (e: Exception) {
        ""
    }
}

fun deriveServiceUrl(base: String, port: Int): String {
    if (base.isEmpty()) return ""
    return try {
        val uri = java.net.URI(base)
        if (uri.host != null) "http://${uri.host}:$port" else ""
    } catch (e: Exception) {
        ""
    }
}

// Live transcription timing
private const val CHUNK_INTERVAL_MS = 1800L
private const val CHUNK_INITIAL_DELAY_MS = 1500L

// ═══════════════════════════════════════════════════════════════
// 🏠 ACTIVITY
// ═══════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.pimax.whisperflow.sync.UiLanguage.wrap(base))
    private var audioRecorder: AudioRecorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH)) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(androidx.compose.ui.res.stringResource(R.string.memory_phone_apk_on_watch), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
            return
        }
        audioRecorder = AudioRecorder(this)
        SoundFeedback.initialize(this)
        enableEdgeToEdge()

        WatchInboxWorker.enqueue(applicationContext)
        setContent {
            WhisperFlowTheme {
                MainScreen(audioRecorder!!)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH)) NoteVaultSyncWorker.enqueue(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder?.release()
        SoundFeedback.release()
    }
}

// ═══════════════════════════════════════════════════════════════
// 🎨 THEME
// ═══════════════════════════════════════════════════════════════

@Composable
fun WhisperFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            secondary = Copper,
            tertiary = AmberLight,
            background = DeepNoir,
            surface = DarkSlate,
            onPrimary = DeepNoir,
            onSecondary = DeepNoir,
            onBackground = WarmWhite,
            onSurface = WarmWhite,
            error = CoralRed
        ),
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════
// 📱 MAIN SCREEN
// ═══════════════════════════════════════════════════════════════

@Composable
fun MainScreen(audioRecorder: AudioRecorder) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(androidx.compose.ui.res.stringResource(R.string.nav_record),
        androidx.compose.ui.res.stringResource(R.string.notes_title), androidx.compose.ui.res.stringResource(R.string.nav_settings))
    val mainRoutes = listOf(0, 1, 3)
    androidx.activity.compose.BackHandler(enabled = selectedTab == 2 || selectedTab == 4) {
        selectedTab = if (selectedTab == 2) 3 else 1
    }
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsDataStore(context) }
    val modelManager = remember { ModelManager(context) }
    val transcriptionEngine = remember { TranscriptionEngine(context) }
    val whisperServer = remember { WhisperServer(context, transcriptionEngine) }

    var serverUrl by remember { mutableStateOf(SettingsDataStore.DEFAULT_SERVER_URL) }
    var language by remember { mutableStateOf(SettingsDataStore.DEFAULT_LANGUAGE) }
    var task by remember { mutableStateOf(SettingsDataStore.DEFAULT_TASK) }
    var inferenceMode by remember { mutableStateOf(SettingsDataStore.DEFAULT_INFERENCE_MODE) }
    var localModel by remember { mutableStateOf(SettingsDataStore.DEFAULT_LOCAL_MODEL) }
    var isServerOnline by remember { mutableStateOf<Boolean?>(null) }
    var isGatewayOnline by remember { mutableStateOf<Boolean?>(null) }
    var liveChunkingEnabled by remember { mutableStateOf(true) }

    // All service URLs derived from serverUrl — configure server once in Settings
    val gatewayUrl = deriveServiceUrl(serverUrl, 8068)
    val tasksUrl = deriveServiceUrl(serverUrl, 8058)
    val streakUrl = deriveServiceUrl(serverUrl, 8055)
    val assistantUrl = deriveServiceUrl(serverUrl, 8073)

    // Tasks connection state
    var tasksEnabled by remember { mutableStateOf(false) }
    var isTasksOnline by remember { mutableStateOf<Boolean?>(null) }

    // Streak Tracker connection state
    var streakEnabled by remember { mutableStateOf(false) }
    var isStreakOnline by remember { mutableStateOf<Boolean?>(null) }

    // Voice Assistant connection state
    var assistantEnabled by remember { mutableStateOf(false) }
    var isAssistantOnline by remember { mutableStateOf<Boolean?>(null) }

    var isRefreshingConnections by remember { mutableStateOf(false) }

    // pimax-auth identity state (resolved when Connections tab opens)
    var userIdentity by remember { mutableStateOf<PimaxAuthApi.WhoAmIResult?>(null) }
    var isLoadingIdentity by remember { mutableStateOf(false) }

    // Assistant settings
    var assistantStopMode by remember { mutableStateOf(SettingsDataStore.DEFAULT_ASSISTANT_STOP_MODE) }
    var assistantAnimationMode by remember { mutableStateOf(SettingsDataStore.DEFAULT_ANIMATION_MODE) }
    var volumePttEnabled by remember { mutableStateOf(false) }
    var volumePttKeyCode by remember { mutableStateOf(SettingsDataStore.DEFAULT_VOLUME_PTT_KEY_CODE) }
    var assistantAnimationSpeed by remember { mutableStateOf(1.0f) }

    // Transcription tuning settings
    var chunkIntervalMs by remember { mutableLongStateOf(SettingsDataStore.DEFAULT_CHUNK_INTERVAL_MS) }
    var silenceTimeoutMs by remember { mutableLongStateOf(SettingsDataStore.DEFAULT_SILENCE_TIMEOUT_MS) }
    var hallucinationThreshold by remember { mutableStateOf(SettingsDataStore.DEFAULT_HALLUCINATION_THRESHOLD) }
    var toastTemplate by remember { mutableStateOf(SettingsDataStore.DEFAULT_TOAST_TEMPLATE) }
    var localServerEnabled by remember { mutableStateOf(false) }
    var soundEnabled by remember { mutableStateOf(true) }
    var ramblerEnabled by remember { mutableStateOf(false) }
    var voiceKeyboardMode by remember { mutableStateOf(false) }
    var vibrationEnabled by remember { mutableStateOf(true) }
    var vibrationIntensity by remember { mutableFloatStateOf(0.8f) }
    var localServerPort by remember { mutableIntStateOf(8060) }
    val isLocalServerRunning by whisperServer.isRunning.collectAsState()

    // Local model state
    val installedModels by modelManager.installedModels.collectAsState()
    val downloadState by modelManager.downloadState.collectAsState()
    val engineState by transcriptionEngine.state.collectAsState()

    val recorderState by audioRecorder.state.collectAsState()
    val amplitude by audioRecorder.amplitude.collectAsState()
    val durationMs by audioRecorder.durationMs.collectAsState()

    var transcriptionText by remember { mutableStateOf("") }
    var liveChunks by remember { mutableStateOf(listOf<String>()) }
    var isTranscribing by remember { mutableStateOf(false) }
    var transcriptionError by remember { mutableStateOf<String?>(null) }
    var transcriptionTimeMs by remember { mutableLongStateOf(0L) }

    // Keep screen on while recording or transcribing to prevent phone sleep
    val isRecording = recorderState == AudioRecorder.State.RECORDING
    DisposableEffect(isRecording, isTranscribing) {
        val keepOn = isRecording || isTranscribing
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    val amplitudeHistory = remember { mutableStateListOf<Float>() }
    var historyJson by remember { mutableStateOf("[]") }

    var hasPermission by remember { mutableStateOf(audioRecorder.hasPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    // File picker for attaching audio
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isTranscribing = true
                transcriptionError = null
                transcriptionText = ""
                liveChunks = emptyList()
                val file = copyUriToCache(context, uri)
                if (file != null) {
                    val api = WhisperApi(serverUrl)
                    val result = api.transcribe(file, language, task)
                    isTranscribing = false
                    if (result.success) {
                        transcriptionText = result.text
                        transcriptionTimeMs = result.durationMs
                        settingsStore.updateLastTranscription(result.text)
                        settingsStore.addToHistory(result.text, result.durationMs, result.language, null, "GPU_SERVER")
                        historyJson = settingsStore.history.first()
                        SoundFeedback.playSuccess()
                    } else {
                        transcriptionError = result.error ?: uiText(R.string.ui_transcription_failed)
                        SoundFeedback.playError()
                    }
                    file.delete()
                } else {
                    isTranscribing = false
                    transcriptionError = uiText(R.string.ui_could_not_read_the_selected_file)
                }
            }
        }
    }

    // Refresh history when History tab is selected
    LaunchedEffect(selectedTab) {
        if (selectedTab == 4) {
            historyJson = settingsStore.history.first()
        }
    }

    // Load settings
    LaunchedEffect(Unit) {
        serverUrl = settingsStore.serverUrl.first()
        language = settingsStore.language.first()
        task = settingsStore.task.first()
        inferenceMode = settingsStore.inferenceMode.first()
        localModel = settingsStore.localModel.first()
        historyJson = settingsStore.history.first()
        val lastText = settingsStore.lastTranscription.first()
        if (lastText.isNotEmpty()) transcriptionText = lastText

        // Load connection enabled states
        tasksEnabled = settingsStore.tasksEnabled.first()
        streakEnabled = settingsStore.streakEnabled.first()
        assistantEnabled = settingsStore.assistantEnabled.first()

        // Load assistant settings
        assistantStopMode = settingsStore.assistantStopMode.first()
        assistantAnimationMode = settingsStore.assistantAnimationMode.first()
        volumePttEnabled = settingsStore.volumePttEnabled.first()
        volumePttKeyCode = settingsStore.volumePttKeyCode.first()
        assistantAnimationSpeed = settingsStore.assistantAnimationSpeed.first()

        // Load transcription tuning settings
        chunkIntervalMs = settingsStore.chunkIntervalMs.first()
        silenceTimeoutMs = settingsStore.silenceTimeoutMs.first()
        hallucinationThreshold = settingsStore.hallucinationThreshold.first()
        toastTemplate = settingsStore.toastTemplate.first()
        localServerEnabled = settingsStore.localServerEnabled.first()
        soundEnabled = settingsStore.soundEnabled.first()
        vibrationEnabled = settingsStore.vibrationEnabled.first()
        vibrationIntensity = settingsStore.vibrationIntensity.first()
        ramblerEnabled = settingsStore.ramblerEnabled.first()
        voiceKeyboardMode = settingsStore.voiceKeyboardMode.first()
        SoundFeedback.soundEnabled = soundEnabled
        SoundFeedback.vibrationEnabled = vibrationEnabled
        SoundFeedback.vibrationIntensity = vibrationIntensity
        localServerPort = settingsStore.localServerPort.first()

        // Configure transcription engine
        transcriptionEngine.configureServer(serverUrl, language, gatewayUrl)
        transcriptionEngine.setMode(
            when (inferenceMode) {
                "LOCAL" -> TranscriptionEngine.TranscriptionMode.LOCAL
                "LLM_GATEWAY" -> TranscriptionEngine.TranscriptionMode.LLM_GATEWAY
                else -> TranscriptionEngine.TranscriptionMode.GPU_SERVER
            }
        )

        // Initialize local engine if in LOCAL mode and model is installed
        if (inferenceMode == "LOCAL") {
            try {
                val model = ModelManager.WhisperModel.valueOf(localModel)
                if (model in modelManager.installedModels.value) {
                    transcriptionEngine.initializeLocal(model)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize local engine: ${e.message}")
            }
        }
    }

    // Lifecycle for local server
    LaunchedEffect(localServerEnabled, localServerPort) {
        if (localServerEnabled) {
            whisperServer.start(localServerPort)
        } else {
            whisperServer.stop()
        }
    }

    // Health check (only in GPU_SERVER mode)
    LaunchedEffect(serverUrl, inferenceMode) {
        if (inferenceMode == "GPU_SERVER") {
            val api = WhisperApi(serverUrl)
            isServerOnline = api.checkHealth()
        }
    }

    // LLM Gateway health check
    LaunchedEffect(gatewayUrl, inferenceMode) {
        if (inferenceMode == "LLM_GATEWAY") {
            val api = WhisperApi(gatewayUrl)
            isGatewayOnline = api.checkHealth()
        }
    }

    // Tasks service health check
    LaunchedEffect(tasksUrl, tasksEnabled) {
        if (tasksEnabled) {
            val tasksApi = TasksApi(tasksUrl)
            isTasksOnline = tasksApi.checkConnection()
        }
    }

    // Streak service health check
    LaunchedEffect(streakUrl, streakEnabled) {
        if (streakEnabled) {
            val streakApi = WhisperHookApi(streakUrl, "Streak")
            isStreakOnline = streakApi.checkConnection()
        }
    }

    // Assistant service health check
    LaunchedEffect(assistantUrl, assistantEnabled) {
        if (assistantEnabled) {
            val assistantApi = WhisperHookApi(assistantUrl, "Assistant")
            isAssistantOnline = assistantApi.checkConnection()
        }
    }

    // Amplitude tracking for waveform
    LaunchedEffect(recorderState) {
        if (recorderState == AudioRecorder.State.RECORDING) {
            amplitudeHistory.clear()
            while (recorderState == AudioRecorder.State.RECORDING) {
                amplitudeHistory.add(amplitude)
                if (amplitudeHistory.size > 120) {
                    amplitudeHistory.removeAt(0)
                }
                delay(50)
            }
        }
    }

    // Live transcription loop
    // GPU_SERVER: fire-and-forget parallel chunks
    // LOCAL: sequential processing to avoid TFLite concurrent access crashes
    LaunchedEffect(recorderState, liveChunkingEnabled, inferenceMode) {
        if (recorderState == AudioRecorder.State.RECORDING && liveChunkingEnabled) {
            delay(CHUNK_INITIAL_DELAY_MS)

            if (inferenceMode == "LOCAL") {
                // LOCAL MODE: Sequential processing - TFLite is NOT thread-safe
                while (isActive && recorderState == AudioRecorder.State.RECORDING && liveChunkingEnabled) {
                    val chunkResult = audioRecorder.saveChunk()
                    if (chunkResult != null) {
                        val capturedPeak = chunkResult.peakAmplitude
                        try {
                            val wavBytes = chunkResult.file.readBytes()
                            chunkResult.file.delete()

                            // Skip silent chunks
                            if (capturedPeak < 0.01f) {
                                Log.d(TAG, "Skipping silent chunk (peak: $capturedPeak)")
                            } else {
                                // Process sequentially - await result before next chunk
                                // Use NonCancellable to ensure transcription completes even if recording stops
                                val result = withContext(NonCancellable + Dispatchers.IO) {
                                    transcriptionEngine.transcribeWav(wavBytes, language)
                                }
                                val text = result.text.trim()
                                val filtered = WhisperApi.filterHallucinations(text)

                                if (!filtered.isNullOrBlank()) {
                                    withContext(Dispatchers.Main) {
                                        liveChunks = liveChunks + filtered
                                        transcriptionText = liveChunks.joinToString(" ")
                                    }
                                    Log.d(TAG, "Live chunk (LOCAL): $filtered")
                                    if (ramblerEnabled) {
                                        scope.launch(Dispatchers.IO) {
                                            val gatewayUrlVal = settingsStore.gatewayUrl.first()
                                            val api = WhisperApi("")
                                            val joined = liveChunks.joinToString(" ")
                                            val cleaned = api.cleanRamblerText(gatewayUrlVal, joined)
                                            if (cleaned != null) {
                                                withContext(Dispatchers.Main) {
                                                    transcriptionText = cleaned.trim()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "LOCAL chunk transcription failed: ${e.message}")
                            chunkResult.file.delete()
                        }
                    }
                    delay(chunkIntervalMs)
                }
            } else {
                // GPU_SERVER MODE: Parallel fire-and-forget for better responsiveness
                while (isActive && recorderState == AudioRecorder.State.RECORDING && liveChunkingEnabled) {
                    val chunkResult = audioRecorder.saveChunk()
                    if (chunkResult != null) {
                        val capturedLang = language
                        val capturedPeak = chunkResult.peakAmplitude
                        scope.launch(Dispatchers.IO) {
                            try {
                                val wavBytes = chunkResult.file.readBytes()
                                chunkResult.file.delete()

                                if (capturedPeak < 0.01f) {
                                    Log.d(TAG, "Skipping silent chunk (peak: $capturedPeak)")
                                    return@launch
                                }

                                val result = transcriptionEngine.transcribeWav(wavBytes, capturedLang)
                                val text = result.text.trim()
                                val filtered = WhisperApi.filterHallucinations(text)

                                if (!filtered.isNullOrBlank()) {
                                    withContext(Dispatchers.Main) {
                                        liveChunks = liveChunks + filtered
                                        transcriptionText = liveChunks.joinToString(" ")
                                    }
                                    Log.d(TAG, "Live chunk (GPU): $filtered")
                                    if (ramblerEnabled) {
                                        scope.launch(Dispatchers.IO) {
                                            val gatewayUrlVal = settingsStore.gatewayUrl.first()
                                            val api = WhisperApi("")
                                            val joined = liveChunks.joinToString(" ")
                                            val cleaned = api.cleanRamblerText(gatewayUrlVal, joined)
                                            if (cleaned != null) {
                                                withContext(Dispatchers.Main) {
                                                    transcriptionText = cleaned.trim()
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "GPU chunk transcription failed: ${e.message}")
                                chunkResult.file.delete()
                            }
                        }
                    }
                    delay(chunkIntervalMs)
                }
            }
        }
    }

    val onRecord: () -> Unit = {
        Log.d(TAG, "onRecord tapped: hasPermission=$hasPermission, recorderState=$recorderState, inferenceMode=$inferenceMode, engineState=$engineState")
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            when (recorderState) {
                AudioRecorder.State.IDLE, AudioRecorder.State.STOPPED -> {
                    Log.d(TAG, "Ready to record check: inferenceMode=$inferenceMode, isServerOnline=$isServerOnline, engineState=$engineState")
                    if (inferenceMode == "GPU_SERVER" && isServerOnline == false) {
                        Log.w(TAG, "Blocked: GPU server offline")
                        Toast.makeText(context, uiText(R.string.ui_gpu_server_offline_cannot_record), Toast.LENGTH_SHORT).show()
                    } else if (inferenceMode == "LOCAL" && engineState !is TranscriptionEngine.EngineState.Ready) {
                        Log.w(TAG, "Blocked: Local engine not ready. State: $engineState")
                        Toast.makeText(context, uiText(R.string.ui_local_model_not_ready_download_a_model_first), Toast.LENGTH_SHORT).show()
                    } else {
                        Log.d(TAG, "Starting recording in $inferenceMode mode")
                        transcriptionError = null
                        transcriptionText = ""
                        liveChunks = emptyList()
                        amplitudeHistory.clear()
                        SoundFeedback.playStart()
                        audioRecorder.start()
                    }
                }
                AudioRecorder.State.RECORDING -> {
                    SoundFeedback.playStop()
                    val wavFile = audioRecorder.stop()
                    if (wavFile != null && !liveChunkingEnabled) {
                        // Batch mode: transcribe the full recording
                        isTranscribing = true
                        transcriptionError = null
                        scope.launch {
                            try {
                                val wavBytes = wavFile.readBytes()
                                val result = transcriptionEngine.transcribeWav(wavBytes, language)
                                isTranscribing = false
                                val rawText = result.text.trim()
                                var finalText = rawText
                                transcriptionText = rawText
                                transcriptionTimeMs = result.processingTimeMs

                                if (ramblerEnabled && rawText.isNotBlank()) {
                                    val gatewayUrlVal = settingsStore.gatewayUrl.first()
                                    val api = WhisperApi("")
                                    val cleaned = api.cleanRamblerText(gatewayUrlVal, rawText)
                                    if (cleaned != null && cleaned.trim() != rawText) {
                                        finalText = cleaned.trim()
                                        transcriptionText = finalText
                                    }
                                }

                                settingsStore.updateLastTranscription(finalText)
                                val audioPath = settingsStore.saveRecordingPermanently(wavFile)
                                val modeStr = when (inferenceMode) {
                                    "LOCAL" -> "LOCAL:$localModel"
                                    "LLM_GATEWAY" -> "GATEWAY:whisper-large-v3-turbo"
                                    else -> "GPU_SERVER"
                                }

                                // Send to Tasks if enabled (skip when Assistant routes commands)
                                var sentToTasks = false
                                if (tasksEnabled && !assistantEnabled && finalText.isNotBlank()) {
                                    val tasksApi = TasksApi(tasksUrl)
                                    val taskResult = tasksApi.createTask(finalText)
                                    sentToTasks = taskResult.success
                                    if (sentToTasks) SoundFeedback.playTaskSaved()
                                }

                                // Voice Assistant (routes streak + chat; skip direct Streak)
                                var assistantMessage: String? = null
                                if (assistantEnabled && finalText.isNotBlank()) {
                                    val assistantApi = WhisperHookApi(assistantUrl, "Assistant")
                                    val hookResult = assistantApi.send(finalText)
                                    assistantMessage = hookResult.message.ifBlank { uiText(R.string.ui_assistant_unreachable) }
                                }

                                // Send to Streak Tracker if enabled (skip when Assistant is ON)
                                var streakMessage: String? = null
                                if (!assistantEnabled && streakEnabled && finalText.isNotBlank()) {
                                    val streakApi = WhisperHookApi(streakUrl, "Streak")
                                    val hookResult = streakApi.send(finalText)
                                    if (hookResult.success && hookResult.action != "ignored") {
                                        streakMessage = hookResult.message
                                    }
                                }

                                // Show combined toast
                                val toastParts = mutableListOf<String>()
                                if (sentToTasks) toastParts.add("✅ Task")
                                if (assistantMessage != null) toastParts.add(assistantMessage)
                                if (streakMessage != null) toastParts.add(streakMessage)
                                if (toastParts.isNotEmpty()) {
                                    Toast.makeText(context, toastParts.joinToString(" • "), Toast.LENGTH_LONG).show()
                                }

                                settingsStore.addToHistory(finalText, result.processingTimeMs, result.language, audioPath, modeStr, sentToTasks)
                                historyJson = settingsStore.history.first()
                                SoundFeedback.playSuccess()
                                Log.d(TAG, "Transcription complete (${result.mode}): ${result.text.take(50)}...")
                            } catch (e: Exception) {
                                isTranscribing = false
                                transcriptionError = e.message ?: uiText(R.string.ui_transcription_failed)
                                Log.e(TAG, "Transcription error: ${e.message}")
                                SoundFeedback.playError()
                            }
                        }
                    } else if (wavFile != null && liveChunkingEnabled) {
                        // Live mode: send final chunk + finalize
                        scope.launch {
                            isTranscribing = true
                            try {
                                val wavBytes = wavFile.readBytes()
                                val result = transcriptionEngine.transcribeWav(wavBytes, language)
                                isTranscribing = false
                                if (result.text.isNotBlank()) {
                                    var finalText = result.text.trim()
                                    transcriptionText = finalText
                                    transcriptionTimeMs = result.processingTimeMs

                                    if (ramblerEnabled && finalText.isNotBlank()) {
                                        val gatewayUrlVal = settingsStore.gatewayUrl.first()
                                        val api = WhisperApi("")
                                        val cleaned = api.cleanRamblerText(gatewayUrlVal, finalText)
                                        if (cleaned != null && cleaned.trim() != finalText) {
                                            finalText = cleaned.trim()
                                            transcriptionText = finalText
                                        }
                                    }

                                    settingsStore.updateLastTranscription(finalText)
                                    val audioPath = settingsStore.saveRecordingPermanently(wavFile)
                                    val modeStr = if (inferenceMode == "LOCAL") "LOCAL:$localModel" else "GPU_SERVER"

                                    // Send to Tasks if enabled
                                    var sentToTasks = false
                                    if (tasksEnabled && !assistantEnabled && finalText.isNotBlank()) {
                                        val tasksApi = TasksApi(tasksUrl)
                                        val taskResult = tasksApi.createTask(finalText)
                                        sentToTasks = taskResult.success
                                        if (sentToTasks) SoundFeedback.playTaskSaved()
                                    }

                                    // Voice Assistant
                                    var assistantMessage: String? = null
                                    if (assistantEnabled && finalText.isNotBlank()) {
                                        val assistantApi = WhisperHookApi(assistantUrl, "Assistant")
                                        val hookResult = assistantApi.send(finalText)
                                        assistantMessage = hookResult.message.ifBlank { uiText(R.string.ui_assistant_unreachable) }
                                    }

                                    // Send to Streak Tracker if enabled (skip when Assistant is ON)
                                    var streakMessage: String? = null
                                    if (!assistantEnabled && streakEnabled && finalText.isNotBlank()) {
                                        val streakApi = WhisperHookApi(streakUrl, "Streak")
                                        val hookResult = streakApi.send(finalText)
                                        if (hookResult.success && hookResult.action != "ignored") {
                                            streakMessage = hookResult.message
                                        }
                                    }

                                    // Show combined toast
                                    val toastParts = mutableListOf<String>()
                                    if (sentToTasks) toastParts.add("✅ Task")
                                    if (assistantMessage != null) toastParts.add(assistantMessage)
                                    if (streakMessage != null) toastParts.add(streakMessage)
                                    if (toastParts.isNotEmpty()) {
                                        Toast.makeText(context, toastParts.joinToString(" • "), Toast.LENGTH_LONG).show()
                                    }

                                    settingsStore.addToHistory(result.text, result.processingTimeMs, result.language, audioPath, modeStr, sentToTasks)
                                    historyJson = settingsStore.history.first()
                                    SoundFeedback.playSuccess()
                                    Log.d(TAG, "Live transcription finalized (${result.mode}): ${result.text.take(50)}...")
                                }
                            } catch (e: Exception) {
                                isTranscribing = false
                                Log.e(TAG, "Live transcription error: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }



    // Load pimax-auth identity whenever the Connections tab (index 2) is opened
    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) {
            isLoadingIdentity = true
            userIdentity = PimaxAuthApi(authUrlFromTasksUrl(tasksUrl)).whoami()
            isLoadingIdentity = false
        }
    }

    Scaffold(
        containerColor = DeepNoir,
        bottomBar = {
            BottomNavBar(
                tabs = tabs,
                selectedIndex = if (selectedTab == 4) 1 else mainRoutes.indexOf(selectedTab).takeIf { it >= 0 } ?: 2,
                onSelect = { selectedTab = mainRoutes[it] }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> RecordTab(
                    recorderState = recorderState,
                    amplitude = amplitude,
                    amplitudeHistory = amplitudeHistory,
                    durationMs = durationMs,
                    isTranscribing = isTranscribing,
                    transcriptionText = transcriptionText,
                    transcriptionError = transcriptionError,
                    transcriptionTimeMs = transcriptionTimeMs,
                    isServerOnline = isServerOnline,
                    hasPermission = hasPermission,
                    liveMode = liveChunkingEnabled,
                    inferenceMode = inferenceMode,
                    engineState = engineState,
                    localModelName = localModel,
                    transcriptionEngine = transcriptionEngine,
                    onLiveModeToggle = { enabled ->
                        liveChunkingEnabled = enabled
                        scope.launch { settingsStore.updateLiveChunkingEnabled(enabled) }
                    },
                    onRecord = onRecord,
                    onAttachFile = { filePickerLauncher.launch("audio/*") },
                    onClear = {
                        transcriptionText = ""
                        transcriptionError = null
                        liveChunks = emptyList()
                        audioRecorder.reset()
                        amplitudeHistory.clear()
                    },
                    onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", transcriptionText))
                        Toast.makeText(context, uiText(R.string.ui_copied), Toast.LENGTH_SHORT).show()
                    },
                    isServerRunning = isLocalServerRunning
                )
                1 -> NotesScreen(onOpenRecordings = { selectedTab = 4 })
                4 -> HistoryTab(
                    historyJson = historyJson,
                    onRefresh = {
                        scope.launch {
                            historyJson = settingsStore.history.first()
                        }
                    },
                    onClear = {
                        scope.launch {
                            settingsStore.clearHistory()
                            historyJson = "[]"
                        }
                    },
                    onCopy = { text ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", text))
                        Toast.makeText(context, uiText(R.string.ui_copied), Toast.LENGTH_SHORT).show()
                    },
                    onDelete = { timestamp ->
                        scope.launch {
                            settingsStore.deleteHistoryEntry(timestamp)
                            historyJson = settingsStore.history.first()
                        }
                    }
                )
                2 -> ConnectionsTab(
                    tasksEnabled = tasksEnabled,
                    isTasksOnline = isTasksOnline,
                    streakEnabled = streakEnabled,
                    isStreakOnline = isStreakOnline,
                    assistantEnabled = assistantEnabled,
                    isAssistantOnline = isAssistantOnline,
                    userIdentity = userIdentity,
                    isLoadingIdentity = isLoadingIdentity,
                    onRefreshIdentity = {
                        scope.launch {
                            isLoadingIdentity = true
                            userIdentity = PimaxAuthApi(deriveServiceUrl(serverUrl, 8100)).whoami()
                            isLoadingIdentity = false
                        }
                    },
                    onTasksEnabledChange = { enabled ->
                        tasksEnabled = enabled
                        scope.launch {
                            settingsStore.updateTasksEnabled(enabled)
                            if (enabled) {
                                isTasksOnline = null
                                val tasksApi = TasksApi(tasksUrl)
                                isTasksOnline = tasksApi.checkConnection()
                            }
                        }
                    },
                    onTestTasksConnection = {
                        scope.launch {
                            isTasksOnline = null
                            val tasksApi = TasksApi(tasksUrl)
                            isTasksOnline = tasksApi.checkConnection()
                        }
                    },
                    onStreakEnabledChange = { enabled ->
                        streakEnabled = enabled
                        scope.launch {
                            settingsStore.updateStreakEnabled(enabled)
                            if (enabled) {
                                isStreakOnline = null
                                val streakApi = WhisperHookApi(streakUrl, "Streak")
                                isStreakOnline = streakApi.checkConnection()
                            }
                        }
                    },
                    onTestStreakConnection = {
                        scope.launch {
                            isStreakOnline = null
                            val streakApi = WhisperHookApi(streakUrl, "Streak")
                            isStreakOnline = streakApi.checkConnection()
                        }
                    },
                    onAssistantEnabledChange = { enabled ->
                        assistantEnabled = enabled
                        scope.launch {
                            settingsStore.updateAssistantEnabled(enabled)
                            if (enabled) {
                                // Exclusive routing: Assistant replaces direct Tasks/Streak hooks
                                tasksEnabled = false
                                streakEnabled = false
                                settingsStore.updateTasksEnabled(false)
                                settingsStore.updateStreakEnabled(false)
                                isTasksOnline = null
                                isStreakOnline = null
                                isAssistantOnline = null
                                val assistantApi = WhisperHookApi(assistantUrl, "Assistant")
                                isAssistantOnline = assistantApi.checkConnection()
                            }
                        }
                    },
                    onTestAssistantConnection = {
                        scope.launch {
                            isAssistantOnline = null
                            val assistantApi = WhisperHookApi(assistantUrl, "Assistant")
                            isAssistantOnline = assistantApi.checkConnection()
                        }
                    },
                    isRefreshing = isRefreshingConnections,
                    onRefresh = {
                        scope.launch {
                            isRefreshingConnections = true
                            // Also refresh identity on pull-to-refresh
                            isLoadingIdentity = true
                            userIdentity = PimaxAuthApi(deriveServiceUrl(serverUrl, 8100)).whoami()
                            isLoadingIdentity = false
                            if (tasksEnabled) {
                                isTasksOnline = null
                                isTasksOnline = TasksApi(tasksUrl).checkConnection()
                            }
                            if (streakEnabled) {
                                isStreakOnline = null
                                isStreakOnline = WhisperHookApi(streakUrl, "Streak").checkConnection()
                            }
                            if (assistantEnabled) {
                                isAssistantOnline = null
                                isAssistantOnline = WhisperHookApi(assistantUrl, "Assistant").checkConnection()
                            }
                            isRefreshingConnections = false
                        }
                    },
                    onSave = {
                        scope.launch {
                            settingsStore.updateTasksEnabled(tasksEnabled)
                            settingsStore.updateStreakEnabled(streakEnabled)
                            settingsStore.updateAssistantEnabled(assistantEnabled)
                            Toast.makeText(context, uiText(R.string.ui_connections_saved), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                3 -> SettingsTab(
                    onOpenConnections = { selectedTab = 2 },
                    serverUrl = serverUrl,
                    language = language,
                    task = task,
                    inferenceMode = inferenceMode,
                    localModel = localModel,
                    isServerOnline = isServerOnline,
                    installedModels = installedModels,
                    downloadState = downloadState,
                    engineState = engineState,
                    assistantStopMode = assistantStopMode,
                    assistantAnimationMode = assistantAnimationMode,
                    volumePttEnabled = volumePttEnabled,
                    volumePttKeyCode = volumePttKeyCode,
                    assistantAnimationSpeed = assistantAnimationSpeed,
                    chunkIntervalMs = chunkIntervalMs,
                    silenceTimeoutMs = silenceTimeoutMs,
                    hallucinationThreshold = hallucinationThreshold,
                    toastTemplate = toastTemplate,
                    liveChunkingEnabled = liveChunkingEnabled,
                    soundEnabled = soundEnabled,
                    vibrationEnabled = vibrationEnabled,
                    vibrationIntensity = vibrationIntensity,
                    ramblerEnabled = ramblerEnabled,
                    voiceKeyboardMode = voiceKeyboardMode,
                    onRamblerEnabledChange = { enabled ->
                        ramblerEnabled = enabled
                        scope.launch {
                            settingsStore.updateRamblerEnabled(enabled)
                        }
                        SoundFeedback.playSuccess()
                    },
                    onVoiceKeyboardModeChange = { enabled ->
                        voiceKeyboardMode = enabled
                        scope.launch {
                            settingsStore.updateVoiceKeyboardMode(enabled)
                        }
                        SoundFeedback.playSuccess()
                    },
                    onSoundEnabledChange = { enabled ->
                        soundEnabled = enabled
                        SoundFeedback.soundEnabled = enabled
                        scope.launch {
                            settingsStore.updateSoundEnabled(enabled)
                        }
                        SoundFeedback.playSuccess()
                    },
                    onVibrationEnabledChange = { enabled ->
                        vibrationEnabled = enabled
                        SoundFeedback.vibrationEnabled = enabled
                        scope.launch {
                            settingsStore.updateVibrationEnabled(enabled)
                        }
                        SoundFeedback.playSuccess()
                    },
                    onVibrationIntensityChange = { intensity ->
                        vibrationIntensity = intensity
                        SoundFeedback.vibrationIntensity = intensity
                        scope.launch {
                            settingsStore.updateVibrationIntensity(intensity)
                        }
                        SoundFeedback.playSuccess()
                    },
                    onServerUrlChange = { serverUrl = it },
                    onLanguageChange = { language = it },
                    onTaskChange = { task = it },
                    onInferenceModeChange = { mode ->
                        inferenceMode = mode
                        // Disable live chunking for LLM Gateway as it's not supported/efficient
                        if (mode == "LLM_GATEWAY") {
                            liveChunkingEnabled = false
                            scope.launch { settingsStore.updateLiveChunkingEnabled(false) }
                        }
                        scope.launch {
                            settingsStore.updateInferenceMode(mode)
                            transcriptionEngine.setMode(
                                when (mode) {
                                    "LOCAL" -> TranscriptionEngine.TranscriptionMode.LOCAL
                                    "LLM_GATEWAY" -> TranscriptionEngine.TranscriptionMode.LLM_GATEWAY
                                    else -> TranscriptionEngine.TranscriptionMode.GPU_SERVER
                                }
                            )
                            // Initialize local engine if switching to LOCAL mode
                            if (mode == "LOCAL") {
                                val model = try { ModelManager.WhisperModel.valueOf(localModel) } catch(e: Exception) { ModelManager.WhisperModel.BASE }
                                if (model in installedModels) {
                                    transcriptionEngine.initializeLocal(model)
                                }
                            }
                        }
                    },
                    onLocalModelChange = { model ->
                        localModel = model
                        scope.launch {
                            settingsStore.updateLocalModel(model)
                            // Re-initialize engine with new model
                            if (inferenceMode == "LOCAL") {
                                val whisperModel = ModelManager.WhisperModel.valueOf(model)
                                transcriptionEngine.initializeLocal(whisperModel)
                            }
                        }
                    },
                    onDownloadModel = { model ->
                        scope.launch {
                            val result = modelManager.downloadModel(model)
                            // Auto-initialize engine after successful download
                            if (result.isSuccess) {
                                localModel = model.name
                                settingsStore.updateLocalModel(model.name)
                                if (inferenceMode == "LOCAL") {
                                    transcriptionEngine.initializeLocal(model)
                                    Log.d(TAG, "Auto-initialized ${model.displayName} after download")
                                }
                            }
                        }
                    },
                    onDeleteModel = { model ->
                        modelManager.deleteModel(model)
                    },
                    onAssistantStopModeChange = { mode ->
                        assistantStopMode = mode
                        scope.launch {
                            settingsStore.updateAssistantStopMode(mode)
                        }
                    },
                    onAssistantAnimationModeChange = { mode ->
                        assistantAnimationMode = mode
                        scope.launch {
                            settingsStore.updateAssistantAnimationMode(mode)
                        }
                    },
                    onVolumePttEnabledChange = { enabled ->
                        volumePttEnabled = enabled
                        scope.launch {
                            settingsStore.updateVolumePttEnabled(enabled)
                        }
                    },
                    onVolumePttKeyCodeChange = { code ->
                        volumePttKeyCode = code
                        scope.launch {
                            settingsStore.updateVolumePttKeyCode(code)
                        }
                    },
                    onAssistantAnimationSpeedChange = { speed ->
                        assistantAnimationSpeed = speed
                        scope.launch {
                            settingsStore.updateAssistantAnimationSpeed(speed)
                        }
                    },
                    onChunkIntervalChange = { chunkIntervalMs = it },
                    onSilenceTimeoutChange = { silenceTimeoutMs = it },
                    onHallucinationThresholdChange = { hallucinationThreshold = it },
                    onToastTemplateChange = { toastTemplate = it },
                    onLiveChunkingEnabledChange = { enabled ->
                        scope.launch { settingsStore.updateLiveChunkingEnabled(enabled) }
                    },
                    localServerEnabled = localServerEnabled,
                    localServerPort = localServerPort,
                    onLocalServerEnabledChange = { enabled ->
                        localServerEnabled = enabled
                        scope.launch { settingsStore.updateLocalServerEnabled(enabled) }
                    },
                    onLocalServerPortChange = { port ->
                        localServerPort = port
                        scope.launch { settingsStore.updateLocalServerPort(port) }
                    },
                    onSave = {
                        scope.launch {
                            settingsStore.updateServerUrl(serverUrl)
                            settingsStore.updateLanguage(language)
                            settingsStore.updateTask(task)
                            settingsStore.updateInferenceMode(inferenceMode)
                            settingsStore.updateLocalModel(localModel)
                            settingsStore.updateAssistantStopMode(assistantStopMode)
                            settingsStore.updateAssistantAnimationMode(assistantAnimationMode)
                            settingsStore.updateChunkIntervalMs(chunkIntervalMs)
                            settingsStore.updateSilenceTimeoutMs(silenceTimeoutMs)
                            settingsStore.updateHallucinationThreshold(hallucinationThreshold)
                            settingsStore.updateToastTemplate(toastTemplate)
                            settingsStore.updateLiveChunkingEnabled(liveChunkingEnabled)
                            settingsStore.updateLocalServerEnabled(localServerEnabled)
                            settingsStore.updateLocalServerPort(localServerPort)
                            settingsStore.updateSoundEnabled(soundEnabled)
                            settingsStore.updateVibrationEnabled(vibrationEnabled)
                            settingsStore.updateVibrationIntensity(vibrationIntensity)

                            // Reconfigure engine with new settings
                            transcriptionEngine.configureServer(serverUrl, language, gatewayUrl)
                            transcriptionEngine.setMode(
                                when (inferenceMode) {
                                    "LOCAL" -> TranscriptionEngine.TranscriptionMode.LOCAL
                                    "LLM_GATEWAY" -> TranscriptionEngine.TranscriptionMode.LLM_GATEWAY
                                    else -> TranscriptionEngine.TranscriptionMode.GPU_SERVER
                                }
                            )

                            if (inferenceMode == "GPU_SERVER") {
                                isServerOnline = null
                                val api = WhisperApi(serverUrl)
                                isServerOnline = api.checkHealth()
                            } else if (inferenceMode == "LLM_GATEWAY") {
                                isGatewayOnline = null
                                val api = WhisperApi(gatewayUrl)
                                isGatewayOnline = api.checkHealth()
                            }
                            Toast.makeText(context, uiText(R.string.ui_settings_saved), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onTestConnection = {
                        scope.launch {
                            isServerOnline = null
                            val api = WhisperApi(serverUrl)
                            isServerOnline = api.checkHealth()
                        }
                    },
                    isGatewayOnline = isGatewayOnline,
                    onTestGatewayConnection = {
                        scope.launch {
                            isGatewayOnline = null
                            val api = WhisperApi(gatewayUrl)
                            isGatewayOnline = api.checkHealth()
                        }
                    }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 📂 FILE HELPERS
// ═══════════════════════════════════════════════════════════════

private suspend fun copyUriToCache(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
    try {
        val fileName = getFileNameFromUri(context, uri) ?: "attached_audio.wav"
        val cacheFile = File(context.cacheDir, "whisperflow_attach_$fileName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        if (cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to copy URI to cache: ${e.message}")
        null
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}

// ═══════════════════════════════════════════════════════════════
// 🕳️ SQUEEZE-TO-HOLE VISIBILITY
// Mirrors CardExpandView.reverse(): card scales toward its top-
// center edge (camera punch hole direction) while the layout
// space collapses in sync. Both driven by the same progress so
// visual size and occupied space shrink together, no clipping fight.
// ═══════════════════════════════════════════════════════════════

@Composable
fun SqueezeToHoleVisibility(
    visible: Boolean,
    durationEnter: Int = 380,
    durationExit: Int = 300,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var showInLayout by remember { mutableStateOf(visible) }

    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible)
            tween(durationEnter, easing = FastOutSlowInEasing)
        else
            tween(durationExit, easing = FastOutLinearInEasing),
        finishedListener = { value -> if (value == 0f) showInLayout = false },
        label = "squeezeProgress"
    )

    LaunchedEffect(visible) {
        if (visible) showInLayout = true
    }

    if (showInLayout) {
        // Single Modifier.layout block: collapse height AND apply layer transform together.
        // placeWithLayer provides GraphicsLayerScope so we get scaleX/Y, alpha, transformOrigin
        // without a separate graphicsLayer import.
        Box(
            modifier = modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val animatedHeight = (placeable.height * progress).toInt().coerceAtLeast(0)
                layout(placeable.width, animatedHeight) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = progress
                        scaleY = progress
                        alpha = progress
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                }
            }
        ) {
            content()
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 🎤 RECORD TAB
// ═══════════════════════════════════════════════════════════════

@Composable
fun RecordTab(
    recorderState: AudioRecorder.State,
    amplitude: Float,
    amplitudeHistory: List<Float>,
    durationMs: Long,
    isTranscribing: Boolean,
    transcriptionText: String,
    transcriptionError: String?,
    transcriptionTimeMs: Long,
    isServerOnline: Boolean?,
    hasPermission: Boolean,
    liveMode: Boolean,
    inferenceMode: String,
    engineState: TranscriptionEngine.EngineState,
    localModelName: String,
    transcriptionEngine: TranscriptionEngine,
    onLiveModeToggle: (Boolean) -> Unit,
    onRecord: () -> Unit,
    onAttachFile: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    isServerRunning: Boolean = false
) {
    val isRecording = recorderState == AudioRecorder.State.RECORDING

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(80)
        isVisible = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))

        // Header
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(500)) + slideInVertically(initialOffsetY = { -30 })
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "WHISPER",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    color = WarmWhite,
                    letterSpacing = 8.sp
                )
                Text(
                    androidx.compose.ui.res.stringResource(R.string.brand_subtitle),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Amber,
                    letterSpacing = 12.sp
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Status row
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 80))
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InferenceModeChip(
                    inferenceMode = inferenceMode,
                    isServerOnline = isServerOnline,
                    engineState = engineState,
                    localModelName = localModelName
                )
                ModeChip(liveMode = liveMode, onToggle = onLiveModeToggle)
            }
        }

        Spacer(Modifier.height(20.dp))

        // Waveform
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 150)) + expandVertically()
        ) {
            WaveformVisualizer(
                amplitudeHistory = amplitudeHistory,
                isRecording = isRecording,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )
        }

        // Live transcription stream - below waveform
        SqueezeToHoleVisibility(
            visible = isRecording && liveMode && transcriptionText.isNotEmpty(),
            durationEnter = 300,
            durationExit = 280
        ) {
            LiveTranscriptionCard(text = transcriptionText)
        }

        Spacer(Modifier.height(12.dp))

        // Duration
        AnimatedVisibility(
            visible = isRecording || durationMs > 0,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecording && liveMode) {
                    val pulseAlpha by rememberInfiniteTransition(label = "liveDot").animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(700),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "liveDotPulse"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(CoralRed.copy(alpha = pulseAlpha))
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    AudioRecorder.formatDuration(durationMs),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    color = if (isRecording) Amber else WarmGray,
                    letterSpacing = 2.sp
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Record Button
        RecordButton(
            isRecording = isRecording,
            isTranscribing = isTranscribing,
            hasPermission = hasPermission,
            onClick = onRecord
        )

        Spacer(Modifier.height(8.dp))

        // Hint
        Text(
            when {
                !hasPermission -> uiText(R.string.ui_tap_to_grant_mic_permission)
                isTranscribing -> when (inferenceMode) {
                    "LOCAL" -> uiText(R.string.ui_processing_locally)
                    "LLM_GATEWAY" -> uiText(R.string.ui_processing_on_gateway)
                    else -> uiText(R.string.ui_processing_on_gpu)
                }
                isRecording && liveMode -> uiText(R.string.ui_live_tap_to_stop_finalize)
                isRecording -> uiText(R.string.ui_recording_tap_to_stop)
                transcriptionText.isNotEmpty() -> uiText(R.string.ui_tap_to_record_again)
                else -> uiText(R.string.ui_tap_to_record)
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = WarmGray,
            letterSpacing = 2.5.sp
        )

        // GPU Activity Monitor - show when in LOCAL mode and active
        SqueezeToHoleVisibility(
            visible = inferenceMode == "LOCAL" && (isRecording || isTranscribing),
            durationEnter = 300,
            durationExit = 260
        ) {
            GpuActivityMonitor(
                transcriptionEngine = transcriptionEngine,
                isTranscribing = isTranscribing
            )
        }

        Spacer(Modifier.height(6.dp))

        // Attach file button
        AnimatedVisibility(
            visible = !isRecording && !isTranscribing,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150))
        ) {
            SmallActionButton(
                text = uiText(R.string.ui_attach_audio_file),
                color = WarmGray,
                onClick = onAttachFile
            )
        }

        Spacer(Modifier.height(16.dp))

        // Transcribing indicator
        SqueezeToHoleVisibility(
            visible = isTranscribing,
            durationEnter = 320,
            durationExit = 280
        ) {
            TranscribingCard(inferenceMode = inferenceMode)
        }

        // Error
        SqueezeToHoleVisibility(
            visible = transcriptionError != null && !isTranscribing,
            durationEnter = 300,
            durationExit = 260
        ) {
            ErrorCard(transcriptionError ?: "")
        }

        // Final transcription result
        SqueezeToHoleVisibility(
            visible = transcriptionText.isNotEmpty() && !isRecording && !isTranscribing,
            durationEnter = 420,
            durationExit = 320
        ) {
            TranscriptionCard(
                text = transcriptionText,
                durationMs = transcriptionTimeMs,
                onCopy = onCopy,
                onClear = onClear
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ═══════════════════════════════════════════════════════════════
// 🔴 RECORD BUTTON
// ═══════════════════════════════════════════════════════════════

@Composable
fun RecordButton(
    isRecording: Boolean,
    isTranscribing: Boolean,
    hasPermission: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "record")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val buttonScale by animateFloatAsState(
        targetValue = if (isRecording) pulseScale else 1f,
        label = "buttonScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isTranscribing -> WarmGray
            isRecording -> CoralRed
            else -> Amber
        },
        animationSpec = tween(300),
        label = "buttonColor"
    )

    val innerCorner by animateFloatAsState(
        targetValue = if (isRecording) 8f else 40f,
        animationSpec = tween(250),
        label = "innerRadius"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(110.dp)
    ) {
        // Outer glow
        if (isRecording) {
            Canvas(modifier = Modifier.size(110.dp)) {
                drawCircle(
                    color = CoralRed.copy(alpha = glowAlpha * 0.25f),
                    radius = size.minDimension / 2
                )
            }
        }

        // Ring
        Canvas(
            modifier = Modifier
                .size(92.dp)
                .scale(buttonScale)
        ) {
            drawCircle(
                color = buttonColor.copy(alpha = 0.12f),
                radius = size.minDimension / 2
            )
            drawCircle(
                color = buttonColor.copy(alpha = 0.45f),
                radius = size.minDimension / 2,
                style = Stroke(width = 2.5f)
            )
        }

        // Inner button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(66.dp)
                .scale(buttonScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(buttonColor, buttonColor.copy(alpha = 0.8f))
                    )
                )
                .clickable(enabled = !isTranscribing) { onClick() }
        ) {
            if (isTranscribing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = WarmWhite,
                    strokeWidth = 2.5.dp
                )
            } else {
                Canvas(modifier = Modifier.size(26.dp)) {
                    drawRoundRect(
                        color = DeepNoir.copy(alpha = 0.9f),
                        cornerRadius = CornerRadius(innerCorner, innerCorner),
                        size = size
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 📊 WAVEFORM VISUALIZER
// ═══════════════════════════════════════════════════════════════

@Composable
fun WaveformVisualizer(
    amplitudeHistory: List<Float>,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 80
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val idlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "idlePhase"
    )

    // Micro-animation: gentle vertical drift
    val breathe by infiniteTransition.animateFloat(
        initialValue = -0.5f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Smoothed amplitudes for fluid bar transitions
    // Use derivedStateOf to properly track list changes
    val smoothAmplitudes = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(0f) } } }

    // Force recomposition when amplitude history changes by reading its size
    val historySize = amplitudeHistory.size
    val historySnapshot = if (historySize > 0) amplitudeHistory.toList() else emptyList()

    // Update smooth amplitudes based on current history
    LaunchedEffect(isRecording, historySize) {
        while (isRecording) {
            for (i in 0 until barCount) {
                val target = if (historySnapshot.isNotEmpty()) {
                    val index = (i.toFloat() / barCount * historySnapshot.size).toInt()
                        .coerceIn(0, historySnapshot.size - 1)
                    if (index < historySnapshot.size) historySnapshot[index] else 0f
                } else 0f
                if (i < smoothAmplitudes.size) {
                    smoothAmplitudes[i] = smoothAmplitudes[i] + (target - smoothAmplitudes[i]) * 0.3f
                }
            }
            delay(16) // ~60fps update rate
        }
    }

    // No background, no border, no clip — the waveform floats unbounded
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp)
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val barWidth = canvasWidth / barCount
            val gap = barWidth * 0.2f
            val effectiveBarWidth = barWidth - gap
            val centerY = canvasHeight / 2 + breathe

            // Subtle center guide line
            drawLine(
                color = WarmGray.copy(alpha = 0.06f),
                start = Offset(0f, centerY),
                end = Offset(canvasWidth, centerY),
                strokeWidth = 1f
            )

            for (i in 0 until barCount) {
                val amplitudeValue = if (historySnapshot.isNotEmpty() && isRecording) {
                    // Read directly from snapshot for immediate response
                    val index = (i.toFloat() / barCount * historySnapshot.size).toInt()
                        .coerceIn(0, historySnapshot.size - 1)
                    if (index < historySnapshot.size) historySnapshot[index] else 0f
                } else if (!isRecording && historySnapshot.isEmpty()) {
                    // Idle: organic multi-wave breathing
                    val phase1 = idlePhase + (i.toFloat() / barCount) * 3.5f
                    val phase2 = idlePhase * 0.7f + (i.toFloat() / barCount) * 5f
                    val wave1 = kotlin.math.sin(phase1.toDouble()).toFloat()
                    val wave2 = kotlin.math.sin(phase2.toDouble()).toFloat() * 0.4f
                    ((wave1 + wave2) * 0.04f + 0.06f).coerceIn(0.02f, 0.14f)
                } else if (historySnapshot.isNotEmpty()) {
                    // Decay: fading out after recording stops
                    val index = (i.toFloat() / barCount * historySnapshot.size).toInt()
                        .coerceIn(0, historySnapshot.size - 1)
                    if (index < historySnapshot.size) historySnapshot[index] * 0.3f else 0.03f
                } else {
                    0.03f
                }

                val amp = amplitudeValue.coerceIn(0.02f, 1f)
                val barHeight = amp * canvasHeight * 0.88f
                val x = i * barWidth + gap / 2

                val color = when {
                    isRecording && amp > 0.75f -> AmberLight
                    isRecording && amp > 0.5f -> Copper
                    isRecording && amp > 0.25f -> Amber
                    isRecording -> AmberDim
                    else -> WarmGray.copy(alpha = 0.25f)
                }

                // Glow pass — soft bloom behind high-amplitude bars
                if (isRecording && amp > 0.2f) {
                    val glowAlpha = (amp * 0.18f).coerceAtMost(0.15f)
                    val glowExpand = effectiveBarWidth * 0.6f
                    drawRoundRect(
                        color = color.copy(alpha = glowAlpha),
                        topLeft = Offset(x - glowExpand / 2, centerY - barHeight * 0.6f),
                        size = Size(effectiveBarWidth + glowExpand, barHeight * 1.2f),
                        cornerRadius = CornerRadius(effectiveBarWidth, effectiveBarWidth)
                    )
                }

                // Main bar
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, centerY - barHeight / 2),
                    size = Size(effectiveBarWidth, barHeight),
                    cornerRadius = CornerRadius(effectiveBarWidth / 2, effectiveBarWidth / 2)
                )

                // Mirror reflection — faint copy below
                if (barHeight > canvasHeight * 0.06f) {
                    val mirrorHeight = barHeight * 0.25f
                    val mirrorAlpha = (amp * 0.12f).coerceAtMost(0.08f)
                    drawRoundRect(
                        color = color.copy(alpha = mirrorAlpha),
                        topLeft = Offset(x, centerY + barHeight / 2 + 2f),
                        size = Size(effectiveBarWidth, mirrorHeight),
                        cornerRadius = CornerRadius(effectiveBarWidth / 2, effectiveBarWidth / 2)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 🔴 LIVE TRANSCRIPTION CARD
// ═══════════════════════════════════════════════════════════════

@Composable
fun LiveTranscriptionCard(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "livePulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveBorder"
    )

    // Typewriter effect: animate visible char count toward full text length
    var displayedCount by remember { mutableIntStateOf(0) }
    var previousText by remember { mutableStateOf("") }
    var flashActive by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (text.length > previousText.length && text.startsWith(previousText)) {
            // New text arrived — typewriter from where we left off
            val startFrom = displayedCount.coerceAtMost(text.length)
            for (i in startFrom until text.length) {
                displayedCount = i + 1
                delay(18L) // ~55 chars/sec — fast but visible
            }
        } else {
            displayedCount = text.length
            if (previousText.isNotEmpty() && text != previousText) {
                flashActive = true
                delay(400L)
                flashActive = false
            }
        }
        previousText = text
    }

    val visibleText = text.take(displayedCount)
    val showCursor = displayedCount < text.length

    // Blinking cursor
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurface)
            .border(1.dp, Amber.copy(alpha = borderAlpha), RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(CoralRed)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "LIVE",
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                color = CoralRed,
                letterSpacing = 2.sp
            )
            if (displayedCount < text.length) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "STREAMING",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    color = Amber.copy(alpha = 0.6f),
                    letterSpacing = 1.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SelectionContainer {
            Text(
                text = buildString {
                    append(visibleText)
                    if (showCursor || displayedCount == text.length) {
                        // Show a blinking cursor block at the end
                    }
                },
                fontSize = 15.sp,
                color = animateColorAsState(
                    targetValue = if (flashActive) Amber else WarmWhite.copy(alpha = 0.85f),
                    animationSpec = tween(durationMillis = 300),
                    label = "textColor"
                ).value,
                lineHeight = 22.sp
            )
        }
        // Blinking cursor bar below text
        if (text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(16.dp)
                    .background(
                        Amber.copy(
                            alpha = if (displayedCount < text.length) cursorAlpha else cursorAlpha * 0.4f
                        )
                    )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 📝 TRANSCRIPTION CARD
// ═══════════════════════════════════════════════════════════════

@Composable
fun TranscriptionCard(
    text: String,
    durationMs: Long,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurface)
            .border(1.dp, Amber.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Emerald)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    uiText(R.string.ui_transcription),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = WarmGray,
                    letterSpacing = 2.sp
                )
            }
            if (durationMs > 0) {
                Text(
                    "%.1fs".format(durationMs / 1000f),
                    fontSize = 10.sp,
                    color = AmberDim,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        var previousText by remember { mutableStateOf("") }
        var flashActive by remember { mutableStateOf(false) }
        LaunchedEffect(text) {
            if (previousText.isNotEmpty() && text != previousText) {
                flashActive = true
                delay(400L)
                flashActive = false
            }
            previousText = text
        }

        SelectionContainer {
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = animateColorAsState(
                    targetValue = if (flashActive) Amber else WarmWhite,
                    animationSpec = tween(durationMillis = 300),
                    label = "textColor"
                ).value,
                lineHeight = 23.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallActionButton(text = uiText(R.string.ui_clear), color = WarmGray, onClick = onClear)
            Spacer(Modifier.width(10.dp))
            SmallActionButton(text = uiText(R.string.ui_copy), color = Amber, onClick = onCopy)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// ⏳ TRANSCRIBING CARD
// ═══════════════════════════════════════════════════════════════

@Composable
fun TranscribingCard(inferenceMode: String = "GPU_SERVER") {
    val dots by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "dotPulse"
    )

    val isLocal = inferenceMode == "LOCAL"
    val isGateway = inferenceMode == "LLM_GATEWAY"
    val color = when {
        isLocal -> Emerald
        isGateway -> SkyBlue
        else -> Amber
    }
    val text = when {
        isLocal -> uiText(R.string.ui_processing_locallyx)
        isGateway -> uiText(R.string.ui_processing_on_gatewayx)
        else -> uiText(R.string.ui_processing_on_gpux)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        color.copy(alpha = 0.04f),
                        color.copy(alpha = 0.08f),
                        color.copy(alpha = 0.04f)
                    )
                )
            )
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = color,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text + ".".repeat(dots.toInt().coerceIn(0, 3)),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = color.copy(alpha = 0.8f)
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// ❌ ERROR CARD
// ═══════════════════════════════════════════════════════════════

@Composable
fun ErrorCard(error: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CoralRed.copy(alpha = 0.07f))
            .border(1.dp, CoralRed.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = null,
            tint = CoralRed,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            error,
            fontSize = 12.sp,
            color = CoralRed.copy(alpha = 0.9f),
            fontWeight = FontWeight.Medium
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// 📊 GPU ACTIVITY MONITOR — macOS-style graph
// ═══════════════════════════════════════════════════════════════

@Composable
fun GpuActivityMonitor(
    transcriptionEngine: TranscriptionEngine,
    isTranscribing: Boolean
) {
    // Track real inference activity from engine
    val isInferenceActive by transcriptionEngine.isInferenceActive.collectAsState()

    // Activity history for the graph (last 30 samples)
    val activityHistory = remember { mutableStateListOf<Float>().apply { repeat(30) { add(0f) } } }

    // Update activity based on REAL inference state (not just isTranscribing)
    LaunchedEffect(Unit) {
        while (true) {
            val isActive = isInferenceActive || isTranscribing
            val newValue = if (isActive) {
                0.5f + (Math.random() * 0.45f).toFloat() // 50-95% when active
            } else {
                0.03f + (Math.random() * 0.07f).toFloat() // 3-10% when idle
            }
            activityHistory.removeAt(0)
            activityHistory.add(newValue)
            delay(100)
        }
    }

    // Glow animation when processing
    val glowAlpha by rememberInfiniteTransition(label = "gpuGlow").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Emerald.copy(alpha = 0.08f),
                        Emerald.copy(alpha = 0.02f)
                    )
                )
            )
            .border(
                1.dp,
                Emerald.copy(alpha = if (isTranscribing) glowAlpha * 0.4f else 0.12f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isTranscribing) Emerald.copy(alpha = glowAlpha) else Emerald.copy(alpha = 0.5f))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isInferenceActive) uiText(R.string.ui_inference_active) else if (isTranscribing) uiText(R.string.neo_processing) else "ADRENO GPU",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isInferenceActive || isTranscribing) Emerald else WarmGray,
                    letterSpacing = 1.sp
                )
            }
            Text(
                "${(activityHistory.last() * 100).toInt()}%",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = if (isInferenceActive || isTranscribing) Emerald else WarmGray
            )
        }

        Spacer(Modifier.height(8.dp))

        // Activity Graph - macOS style
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DeepNoir.copy(alpha = 0.6f))
        ) {
            val width = size.width
            val height = size.height
            val barWidth = width / activityHistory.size
            val padding = 2f

            // Draw bars
            activityHistory.forEachIndexed { index, value ->
                val barHeight = value * (height - padding * 2)
                val x = index * barWidth
                val alpha = 0.3f + (index.toFloat() / activityHistory.size) * 0.7f

                // Bar gradient effect
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Emerald.copy(alpha = alpha * 0.9f),
                            Emerald.copy(alpha = alpha * 0.4f)
                        ),
                        startY = height - barHeight - padding,
                        endY = height - padding
                    ),
                    topLeft = Offset(x + 1, height - barHeight - padding),
                    size = Size(barWidth - 2, barHeight)
                )

                // Top glow on high activity bars
                if (value > 0.5f && index == activityHistory.lastIndex) {
                    drawRect(
                        color = Emerald.copy(alpha = 0.6f),
                        topLeft = Offset(x + 1, height - barHeight - padding),
                        size = Size(barWidth - 2, 3f)
                    )
                }
            }

            // Grid lines
            for (i in 1..3) {
                val y = height * (1 - i / 4f)
                drawLine(
                    color = WarmGray.copy(alpha = 0.1f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 🟢 STATUS CHIPS
// ═══════════════════════════════════════════════════════════════

@Composable
fun ServerStatusChip(isOnline: Boolean?) {
    val color = when (isOnline) {
        true -> Emerald
        false -> CoralRed
        null -> WarmGray
    }
    val pulse by rememberInfiniteTransition(label = "sp").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "pulse"
    )
    val text = when (isOnline) {
        true -> uiText(R.string.ui_gpu_online)
        false -> uiText(R.string.ui_gpu_offline)
        null -> uiText(R.string.neo_checking)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.07f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (isOnline == null) color.copy(alpha = pulse) else color)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun InferenceModeChip(
    inferenceMode: String,
    isServerOnline: Boolean?,
    engineState: TranscriptionEngine.EngineState,
    localModelName: String = ""
) {
    val isLocal = inferenceMode == "LOCAL"
    val isGateway = inferenceMode == "LLM_GATEWAY"
    val isReady = when {
        isLocal -> engineState is TranscriptionEngine.EngineState.Ready
        isGateway -> true
        else -> isServerOnline == true
    }

    val color = when {
        isLocal -> if (isReady) Emerald else WarmGray
        isGateway -> SkyBlue
        isServerOnline == true -> Emerald
        isServerOnline == false -> CoralRed
        else -> WarmGray
    }

    val pulse by rememberInfiniteTransition(label = "modeChip").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "modePulse"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.07f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (!isReady) color.copy(alpha = pulse) else color)
        )
        Spacer(Modifier.width(7.dp))
        if (isLocal) {
            Text(
                if (isReady) localModelName.uppercase() else "LOCAL ⏳",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                letterSpacing = 1.sp
            )
        } else {
            Text(
                when {
                    isGateway -> "GATEWAY"
                    isServerOnline == true -> uiText(R.string.ui_gpu_server)
                    isServerOnline == false -> "GPU ✗"
                    else -> "GPU ⏳"
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun ModeChip(liveMode: Boolean, onToggle: (Boolean) -> Unit) {
    val color = if (liveMode) Copper else WarmGray
    val label = if (liveMode) "LIVE" else "BATCH"

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.07f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .clickable { onToggle(!liveMode) }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (liveMode) {
            val pulseAlpha by rememberInfiniteTransition(label = "modePulse").animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "mp"
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(CoralRed.copy(alpha = pulseAlpha))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(WarmGray)
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 1.sp
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// 📜 HISTORY TAB
// ═══════════════════════════════════════════════════════════════

@Composable
fun HistoryTab(
    historyJson: String,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (Long) -> Unit
) {
    val inboxContext = LocalContext.current
    val inboxRepository = remember { AudioInboxRepository.get(inboxContext) }
    val inboxRows by inboxRepository.recordings.collectAsState(initial = emptyList())
    val entries = remember(historyJson) {
        try {
            val array = JSONArray(historyJson)
            (array.length() - 1 downTo 0).map { i ->
                val obj = array.getJSONObject(i)
                HistoryEntry(
                    text = obj.getString("text"),
                    timestamp = obj.getLong("timestamp"),
                    durationMs = obj.optLong("duration_ms", 0L),
                    language = if (obj.has("language")) obj.getString("language") else null,
                    audioFile = if (obj.has("audio_file")) obj.getString("audio_file") else null,
                    mode = if (obj.has("mode")) obj.getString("mode") else null,
                    sentToTasks = obj.optBoolean("sent_to_tasks", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(80)
        isVisible = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(28.dp))

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400)) + slideInVertically(initialOffsetY = { -20 })
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        uiText(R.string.ui_history),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = WarmWhite,
                        letterSpacing = 4.sp
                    )
                    Text(
                        uiText(R.string.ui_history_count, entries.size),
                        fontSize = 11.sp,
                        color = WarmGray
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallActionButton(text = uiText(R.string.ui_refresh), color = Amber, onClick = onRefresh)
                    if (entries.isNotEmpty()) {
                        SmallActionButton(text = uiText(R.string.ui_clear_all), color = CoralRed, onClick = onClear)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        WatchInboxSection(inboxRows, onCopy)

        if (entries.isEmpty() && inboxRows.isEmpty()) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 150))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎤", fontSize = 36.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(uiText(R.string.ui_no_transcriptions_yet), fontSize = 13.sp, color = WarmGray)
                        Text(uiText(R.string.ui_record_something_to_get_started), fontSize = 11.sp, color = MutedGray)
                    }
                }
            }
        } else {
            entries.forEachIndexed { index, entry ->
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(
                        tween(300, delayMillis = 40 * index.coerceAtMost(12))
                    ) + slideInVertically(
                        initialOffsetY = { 16 },
                        animationSpec = tween(300, delayMillis = 40 * index.coerceAtMost(12))
                    )
                ) {
                    HistoryCard(
                        entry = entry,
                        onCopy = { onCopy(entry.text) },
                        onDelete = { onDelete(entry.timestamp) }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

data class HistoryEntry(
    val text: String,
    val timestamp: Long,
    val durationMs: Long,
    val language: String?,
    val audioFile: String? = null,
    val mode: String? = null,
    val sentToTasks: Boolean = false
)

@Composable
fun HistoryCard(entry: HistoryEntry, onCopy: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val timeString = remember(entry.timestamp) { dateFormat.format(Date(entry.timestamp)) }

    // Audio playback state
    var isPlaying by remember { mutableStateOf(false) }
    val mediaPlayer = remember { mutableStateOf<MediaPlayer?>(null) }

    // Check if audio file exists and get its size
    val audioExists = remember(entry.audioFile) {
        entry.audioFile?.let { path -> File(path).exists() } ?: false
    }
    val audioSize = remember(entry.audioFile) {
        entry.audioFile?.let { path ->
            val file = File(path)
            if (file.exists()) "%.0f KB".format(file.length() / 1024f) else null
        }
    }

    // Cleanup MediaPlayer on dispose
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer.value?.release()
            mediaPlayer.value = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
            .border(1.dp, MutedGray.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .clickable { onCopy() }
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeString, fontSize = 10.sp, color = WarmGray, fontWeight = FontWeight.Medium)
                // Mode chip
                if (entry.mode != null) {
                    Spacer(Modifier.width(8.dp))
                    val isLocal = entry.mode!!.startsWith("LOCAL")
                    val isGateway = entry.mode!!.startsWith("GATEWAY") || entry.mode == "LLM_GATEWAY"
                    val modeLabel = when {
                        entry.mode == "WATCH" -> androidx.compose.ui.res.stringResource(R.string.memory_watch_source)
                        isLocal -> entry.mode!!.removePrefix("LOCAL:").uppercase()
                        isGateway -> {
                            val model = entry.mode!!.removePrefix("GATEWAY:").uppercase()
                            if (model == "LLM_GATEWAY") "GATEWAY" else model.replace("WHISPER-", "").replace("-", " ")
                        }
                        else -> "GPU"
                    }
                    val chipColor = when {
                        isLocal -> Emerald
                        isGateway -> SkyBlue
                        else -> SkyBlue
                    }
                    Text(
                        modeLabel,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = chipColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(chipColor.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                // Tasks indicator
                if (entry.sentToTasks) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        uiText(R.string.ui_task),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Copper,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Copper.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.language != null && entry.language != "Auto") {
                    Text(
                        entry.language.uppercase(),
                        fontSize = 9.sp,
                        color = SkyBlue,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (entry.durationMs > 0) {
                    Text(
                        "%.1fs".format(entry.durationMs / 1000f),
                        fontSize = 10.sp,
                        color = AmberDim
                    )
                }
                // Delete button
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(CoralRed.copy(alpha = 0.1f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onDelete() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "✕",
                        fontSize = 10.sp,
                        color = CoralRed.copy(alpha = 0.7f)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.text,
            fontSize = 13.sp,
            color = WarmWhite.copy(alpha = 0.9f),
            lineHeight = 19.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        // Audio playback row
        if (audioExists && entry.audioFile != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSlate)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (isPlaying) {
                            // Stop
                            mediaPlayer.value?.stop()
                            mediaPlayer.value?.release()
                            mediaPlayer.value = null
                            isPlaying = false
                        } else {
                            // Play
                            try {
                                mediaPlayer.value?.release()
                                val mp = MediaPlayer()
                                mp.setDataSource(entry.audioFile)
                                mp.setOnCompletionListener {
                                    isPlaying = false
                                    it.release()
                                    mediaPlayer.value = null
                                }
                                mp.prepare()
                                mp.start()
                                mediaPlayer.value = mp
                                isPlaying = true
                            } catch (_: Exception) {
                                isPlaying = false
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    if (isPlaying) "⏹" else "▶",
                    fontSize = 12.sp,
                    color = if (isPlaying) CoralRed else Amber
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isPlaying) "Playing..." else uiText(R.string.ui_play_recording),
                    fontSize = 10.sp,
                    color = if (isPlaying) CoralRed else WarmGray,
                    fontWeight = FontWeight.Medium
                )
                if (audioSize != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        audioSize,
                        fontSize = 9.sp,
                        color = MutedGray
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 🔗 CONNECTIONS TAB
// ═══════════════════════════════════════════════════════════════

@Composable
fun ConnectionsTab(
    tasksEnabled: Boolean,
    isTasksOnline: Boolean?,
    streakEnabled: Boolean,
    isStreakOnline: Boolean?,
    assistantEnabled: Boolean,
    isAssistantOnline: Boolean?,
    userIdentity: PimaxAuthApi.WhoAmIResult?,
    isLoadingIdentity: Boolean,
    onRefreshIdentity: () -> Unit,
    onTasksEnabledChange: (Boolean) -> Unit,
    onTestTasksConnection: () -> Unit,
    onStreakEnabledChange: (Boolean) -> Unit,
    onTestStreakConnection: () -> Unit,
    onAssistantEnabledChange: (Boolean) -> Unit,
    onTestAssistantConnection: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSave: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(80)
        isVisible = true
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                containerColor = DarkSlate,
                color = Amber
            )
        }
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(28.dp))

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400)) + slideInVertically(initialOffsetY = { -20 })
        ) {
            Column {
                Text(
                    uiText(R.string.ui_connections),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = WarmWhite,
                    letterSpacing = 4.sp
                )
                Text(
                    uiText(R.string.ui_send_transcriptions_to_pi_services),
                    fontSize = 11.sp,
                    color = WarmGray
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Identity Card
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.neo_identity)) {
                if (isLoadingIdentity) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = Amber,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else if (userIdentity?.authenticated == true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(AmberDim.copy(alpha = 0.25f))
                                .border(1.dp, Amber.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                userIdentity!!.name.firstOrNull()?.uppercaseChar()?.toString() ?: userIdentity!!.id.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Amber
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                userIdentity!!.name.ifEmpty { userIdentity!!.id },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = WarmWhite
                            )
                            if (userIdentity!!.email.isNotEmpty()) {
                                Text(
                                    userIdentity!!.email,
                                    fontSize = 10.sp,
                                    color = WarmGray
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (userIdentity!!.role == "admin") AmberDim.copy(alpha = 0.25f)
                                        else Emerald.copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    userIdentity!!.role.uppercase(),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = if (userIdentity!!.role == "admin") Amber else Emerald
                                )
                            }
                        }
                        IconButton(onClick = onRefreshIdentity, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = uiText(R.string.ui_refresh_identity),
                                tint = Amber.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                uiText(R.string.ui_not_recognized),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CoralRed
                            )
                            Text(
                                userIdentity?.error ?: uiText(R.string.ui_check_tailscale_connection),
                                fontSize = 10.sp,
                                color = WarmGray
                            )
                        }
                        IconButton(onClick = onRefreshIdentity, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = Amber.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Save Button
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 180))
        ) {
            PrimaryButton(text = uiText(R.string.ui_save_connections), onClick = onSave)
        }

        Spacer(Modifier.height(20.dp))

        // Tasks Connection
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 60)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_tasks)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            uiText(R.string.ui_pimax_tasks),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WarmWhite
                        )
                        Text(
                            uiText(R.string.ui_auto_create_tasks_from_transcriptions),
                            fontSize = 10.sp,
                            color = WarmGray
                        )
                    }
                    Switch(
                        checked = tasksEnabled,
                        onCheckedChange = onTasksEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = AmberDim.copy(alpha = 0.5f),
                            uncheckedThumbColor = WarmGray,
                            uncheckedTrackColor = MutedGray.copy(alpha = 0.3f)
                        )
                    )
                }

                AnimatedVisibility(visible = tasksEnabled) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (isTasksOnline) {
                                                true -> Emerald
                                                false -> CoralRed
                                                null -> WarmGray
                                            }
                                        )
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when (isTasksOnline) {
                                        true -> uiText(R.string.neo_connected)
                                        false -> uiText(R.string.neo_offline)
                                        null -> uiText(R.string.neo_checking)
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (isTasksOnline) {
                                        true -> Emerald
                                        false -> CoralRed
                                        null -> WarmGray
                                    },
                                    letterSpacing = 1.sp
                                )
                            }
                            SmallActionButton(text = uiText(R.string.ui_test), color = Amber, onClick = onTestTasksConnection)
                        }
                    }
                }


            }
        }

        Spacer(Modifier.height(14.dp))

        // Streak Tracker Connection
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 120)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_streak_tracker)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            uiText(R.string.ui_habit_tracking),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WarmWhite
                        )
                        Text(
                            uiText(R.string.ui_complete_habits_by_voice),
                            fontSize = 10.sp,
                            color = WarmGray
                        )
                    }
                    Switch(
                        checked = streakEnabled,
                        onCheckedChange = onStreakEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Emerald,
                            checkedTrackColor = Emerald.copy(alpha = 0.5f),
                            uncheckedThumbColor = WarmGray,
                            uncheckedTrackColor = MutedGray.copy(alpha = 0.3f)
                        )
                    )
                }

                AnimatedVisibility(visible = streakEnabled) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (isStreakOnline) {
                                                true -> Emerald
                                                false -> CoralRed
                                                null -> WarmGray
                                            }
                                        )
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when (isStreakOnline) {
                                        true -> uiText(R.string.neo_connected)
                                        false -> uiText(R.string.neo_offline)
                                        null -> uiText(R.string.neo_checking)
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (isStreakOnline) {
                                        true -> Emerald
                                        false -> CoralRed
                                        null -> WarmGray
                                    },
                                    letterSpacing = 1.sp
                                )
                            }
                            SmallActionButton(text = uiText(R.string.ui_test), color = Emerald, onClick = onTestStreakConnection)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Say \"complete [habit]\" or \"status\"",
                            fontSize = 10.sp,
                            color = WarmGray,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Voice Assistant Connection
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 180)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_assistant)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            uiText(R.string.ui_pimax_voice_assistant),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WarmWhite
                        )
                        Text(
                            uiText(R.string.ui_smart_routing_streak_commands_chat),
                            fontSize = 10.sp,
                            color = WarmGray
                        )
                    }
                    Switch(
                        checked = assistantEnabled,
                        onCheckedChange = onAssistantEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = AmberDim.copy(alpha = 0.5f),
                            uncheckedThumbColor = WarmGray,
                            uncheckedTrackColor = MutedGray.copy(alpha = 0.3f)
                        )
                    )
                }

                AnimatedVisibility(visible = assistantEnabled) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (isAssistantOnline) {
                                                true -> Emerald
                                                false -> CoralRed
                                                null -> WarmGray
                                            }
                                        )
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when (isAssistantOnline) {
                                        true -> uiText(R.string.neo_connected)
                                        false -> uiText(R.string.neo_offline)
                                        null -> uiText(R.string.neo_checking)
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (isAssistantOnline) {
                                        true -> Emerald
                                        false -> CoralRed
                                        null -> WarmGray
                                    },
                                    letterSpacing = 1.sp
                                )
                            }
                            SmallActionButton(text = uiText(R.string.ui_test), color = Amber, onClick = onTestAssistantConnection)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            uiText(R.string.neo_streak_help),
                            fontSize = 10.sp,
                            color = WarmGray,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Info
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 240))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSlate.copy(alpha = 0.4f))
                    .padding(14.dp)
            ) {
                Text(
                    uiText(R.string.ui_how_it_works),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = WarmGray,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    uiText(R.string.ui_enabled_connections_receive_your_transcriptions_automatically_each_service_interprets_the_text_and_takes_action),
                    fontSize = 11.sp,
                    color = WarmGray,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "• Assistant: Routes streak commands + general chat (disable Streak when ON)\n• Tasks: Creates a new task\n• Streak: Direct habit matching",
                    fontSize = 10.sp,
                    color = MutedGray,
                    lineHeight = 15.sp
                )
            }
        }



        Spacer(Modifier.height(32.dp))
    } // Column
    } // PullToRefreshBox
}

// ═══════════════════════════════════════════════════════════════
// ⚙️ SETTINGS TAB
// ═══════════════════════════════════════════════════════════════

@Composable
fun SettingsTab(
    serverUrl: String,
    language: String,
    task: String,
    inferenceMode: String,
    localModel: String,
    isServerOnline: Boolean?,
    installedModels: Set<ModelManager.WhisperModel>,
    downloadState: ModelManager.DownloadState,
    engineState: TranscriptionEngine.EngineState,
    assistantStopMode: String,
    assistantAnimationMode: String,
    chunkIntervalMs: Long,
    silenceTimeoutMs: Long,
    hallucinationThreshold: Float,
    toastTemplate: String,
    liveChunkingEnabled: Boolean,
    volumePttEnabled: Boolean,
    volumePttKeyCode: String,
    assistantAnimationSpeed: Float,
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    vibrationIntensity: Float,
    onSoundEnabledChange: (Boolean) -> Unit,
    onVibrationEnabledChange: (Boolean) -> Unit,
    onVibrationIntensityChange: (Float) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onTaskChange: (String) -> Unit,
    onInferenceModeChange: (String) -> Unit,
    onLocalModelChange: (String) -> Unit,
    onDownloadModel: (ModelManager.WhisperModel) -> Unit,
    onDeleteModel: (ModelManager.WhisperModel) -> Unit,
    onAssistantStopModeChange: (String) -> Unit,
    onAssistantAnimationModeChange: (String) -> Unit,
    onVolumePttEnabledChange: (Boolean) -> Unit,
    onAssistantAnimationSpeedChange: (Float) -> Unit,
    onVolumePttKeyCodeChange: (String) -> Unit,
    onChunkIntervalChange: (Long) -> Unit,
    onSilenceTimeoutChange: (Long) -> Unit,
    onHallucinationThresholdChange: (Float) -> Unit,
    onToastTemplateChange: (String) -> Unit,
    onLiveChunkingEnabledChange: (Boolean) -> Unit,
    localServerEnabled: Boolean,
    localServerPort: Int,
    onLocalServerEnabledChange: (Boolean) -> Unit,
    onLocalServerPortChange: (Int) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
    isGatewayOnline: Boolean?,
    onTestGatewayConnection: () -> Unit,
    ramblerEnabled: Boolean,
    onRamblerEnabledChange: (Boolean) -> Unit,
    voiceKeyboardMode: Boolean,
    onVoiceKeyboardModeChange: (Boolean) -> Unit,
    onOpenConnections: () -> Unit = {}
) {
    val languageContext = LocalContext.current
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(80)
        isVisible = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(28.dp))

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400)) + slideInVertically(initialOffsetY = { -20 })
        ) {
            Text(
                uiText(R.string.ui_settings),
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = WarmWhite,
                letterSpacing = 4.sp
            )
        }

        Spacer(Modifier.height(20.dp))

        // Live Chunking Toggle
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 40)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_live_chunking)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            uiText(R.string.ui_enable_real_time_transcription),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarmWhite
                        )
                        Text(
                            uiText(R.string.ui_shows_intermediate_results_while_recording),
                            fontSize = 10.sp,
                            color = WarmGray
                        )
                    }
                    Switch(
                        checked = liveChunkingEnabled,
                        onCheckedChange = onLiveChunkingEnabledChange,
                        enabled = inferenceMode != "LLM_GATEWAY",
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = AmberDim,
                            uncheckedThumbColor = WarmGray,
                            uncheckedTrackColor = DarkSlate,
                            disabledCheckedThumbColor = Amber.copy(alpha = 0.5f),
                            disabledCheckedTrackColor = AmberDim.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        // Inference Mode
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 60)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_inference_mode)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TaskChip(
                        label = uiText(R.string.ui_gpu_server),
                        isSelected = inferenceMode == "GPU_SERVER",
                        onClick = { onInferenceModeChange("GPU_SERVER") },
                        modifier = Modifier.weight(1f)
                    )
                    TaskChip(
                        label = "LOCAL",
                        isSelected = inferenceMode == "LOCAL",
                        onClick = { onInferenceModeChange("LOCAL") },
                        modifier = Modifier.weight(1f)
                    )
                    TaskChip(
                        label = "GATEWAY",
                        isSelected = inferenceMode == "LLM_GATEWAY",
                        onClick = { onInferenceModeChange("LLM_GATEWAY") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (inferenceMode) {
                        "LOCAL" -> uiText(R.string.ui_on_device_tflite_inference_no_internet_required)
                        "LLM_GATEWAY" -> uiText(R.string.ui_ultra_fast_groq_stt_via_llm_gateway)
                        else -> uiText(R.string.ui_transcribe_via_remote_gpu_server_whisper_large_v3)
                    },
                    fontSize = 10.sp,
                    color = if (inferenceMode == "LOCAL" || inferenceMode == "LLM_GATEWAY") Emerald else WarmGray
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Local Model Settings (only visible in LOCAL mode)
        AnimatedVisibility(
            visible = isVisible && inferenceMode == "LOCAL",
            enter = fadeIn(tween(400, delayMillis = 80)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_local_model)) {
                // Model picker
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelManager.WhisperModel.values().forEach { model ->
                        val isInstalled = model in installedModels
                        val isSelected = localModel == model.name
                        val isDownloading = downloadState is ModelManager.DownloadState.Downloading &&
                            (downloadState as ModelManager.DownloadState.Downloading).model == model

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AmberDim.copy(alpha = 0.3f) else DarkSlate)
                                .border(
                                    width = 1.dp,
                                    color = when {
                                        isSelected -> Amber
                                        isInstalled -> Emerald.copy(alpha = 0.5f)
                                        else -> MutedGray.copy(alpha = 0.3f)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = isInstalled && !isDownloading) {
                                    if (isInstalled) onLocalModelChange(model.name)
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        model.displayName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Amber else WarmWhite
                                    )
                                    if (isInstalled) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "✓",
                                            fontSize = 12.sp,
                                            color = Emerald
                                        )
                                    }
                                }
                                Text(
                                    "${model.sizeBytes / 1_000_000}MB • ${model.speedEstimate}",
                                    fontSize = 10.sp,
                                    color = WarmGray
                                )
                                val description = when (model) {
                                    ModelManager.WhisperModel.TINY -> uiText(R.string.ui_fastest_ok_for_short_english_struggles_with_accents_long_audio)
                                    ModelManager.WhisperModel.BASE -> uiText(R.string.ui_best_balance_good_english_multilingual_recommended_for_daily_use)
                                    ModelManager.WhisperModel.SMALL -> uiText(R.string.ui_best_quality_near_gpu_accuracy_slower_needs_more_ram)
                                }
                                Text(
                                    description,
                                    fontSize = 9.sp,
                                    color = if (model == ModelManager.WhisperModel.BASE) Amber.copy(alpha = 0.7f) else WarmGray.copy(alpha = 0.7f),
                                    lineHeight = 12.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }

                            when {
                                isDownloading -> {
                                    val progress = (downloadState as ModelManager.DownloadState.Downloading).progress
                                    Box(contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier.size(24.dp),
                                            color = Amber,
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            "${(progress * 100).toInt()}%",
                                            fontSize = 8.sp,
                                            color = WarmGray
                                        )
                                    }
                                }
                                isInstalled -> {
                                    SmallActionButton(
                                        text = uiText(R.string.ui_delete),
                                        color = CoralRed,
                                        onClick = { onDeleteModel(model) }
                                    )
                                }
                                else -> {
                                    SmallActionButton(
                                        text = uiText(R.string.ui_download),
                                        color = Amber,
                                        onClick = { onDownloadModel(model) }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Engine status
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusColor = when (engineState) {
                        is TranscriptionEngine.EngineState.Ready -> Emerald
                        is TranscriptionEngine.EngineState.Initializing -> Amber
                        is TranscriptionEngine.EngineState.Error -> CoralRed
                        else -> WarmGray
                    }
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (engineState) {
                            is TranscriptionEngine.EngineState.Ready -> uiText(R.string.ui_engine_ready)
                            is TranscriptionEngine.EngineState.Initializing -> uiText(R.string.neo_initializing)
                            is TranscriptionEngine.EngineState.Error -> uiText(R.string.ui_engine_error, (engineState as TranscriptionEngine.EngineState.Error).message)
                            else -> uiText(R.string.ui_engine_uninitialized)
                        },
                        fontSize = 11.sp,
                        color = statusColor
                    )
                }

                // Download status message
                when (val state = downloadState) {
                    is ModelManager.DownloadState.Downloading -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.status,
                            fontSize = 10.sp,
                            color = Amber
                        )
                    }
                    is ModelManager.DownloadState.Failed -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            uiText(R.string.ui_download_error, state.error),
                            fontSize = 10.sp,
                            color = CoralRed
                        )
                    }
                    is ModelManager.DownloadState.Completed -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            uiText(R.string.ui_download_complete),
                            fontSize = 10.sp,
                            color = Emerald
                        )
                    }
                    else -> {}
                }
            }
        }

        // Distributed STT Server
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_distributed_stt_server)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                uiText(R.string.ui_host_local_stt_hub),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarmWhite
                            )
                            Text(
                                uiText(R.string.ui_allows_other_apps_to_use_this_whisper_engine),
                                fontSize = 10.sp,
                                color = WarmGray
                            )
                        }
                        Switch(
                            checked = localServerEnabled,
                            onCheckedChange = onLocalServerEnabledChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Emerald,
                                checkedTrackColor = Emerald.copy(alpha = 0.3f),
                                uncheckedThumbColor = WarmGray,
                                uncheckedTrackColor = DarkSlate
                            )
                        )
                    }

                    if (localServerEnabled) {
                        OutlinedTextField(
                            value = localServerPort.toString(),
                            onValueChange = { onLocalServerPortChange(it.toIntOrNull() ?: 8060) },
                            label = { Text(uiText(R.string.ui_server_port), fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Amber,
                                unfocusedBorderColor = MutedGray,
                                focusedTextColor = WarmWhite,
                                unfocusedTextColor = WarmWhite
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Text(
                            "URL: http://localhost:$localServerPort/transcribe",
                            fontSize = 10.sp,
                            color = Amber.copy(alpha = 0.8f),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // PI Server URL (GPU Server and LLM Gateway both derive from this)
        AnimatedVisibility(
            visible = isVisible && (inferenceMode == "GPU_SERVER" || inferenceMode == "LLM_GATEWAY"),
            enter = fadeIn(tween(400, delayMillis = 80)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.neo_pi_server)) {
                StyledTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    placeholder = "http://your-pi-ip:7860",
                    keyboardType = KeyboardType.Uri
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (inferenceMode == "LLM_GATEWAY") {
                        ServerStatusChip(isGatewayOnline)
                        SmallActionButton(text = uiText(R.string.ui_test), color = Amber, onClick = onTestGatewayConnection)
                    } else {
                        ServerStatusChip(isServerOnline)
                        SmallActionButton(text = uiText(R.string.ui_test), color = Amber, onClick = onTestConnection)
                    }
                }
                if (inferenceMode == "LLM_GATEWAY") {
                    val derivedGateway = deriveServiceUrl(serverUrl, 8068)
                    if (derivedGateway.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Gateway: $derivedGateway",
                            fontSize = 10.sp,
                            color = Emerald.copy(alpha = 0.8f),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Text(
                            uiText(R.string.ui_ultra_fast_relay_via_groq_whisper_large_v3_turbo),
                            fontSize = 10.sp,
                            color = Emerald.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Language
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 160)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_language)) {
                val languages = listOf(
                    "Auto" to uiText(R.string.ui_auto_detect),
                    "English" to "English",
                    "German" to "German",
                    "Spanish" to "Spanish",
                    "French" to "French",
                    "Italian" to "Italian",
                    "Portuguese" to "Portuguese",
                    "Chinese" to "Chinese",
                    "Japanese" to "Japanese",
                    "Korean" to "Korean",
                    "Arabic" to "Arabic",
                    "Russian" to "Russian",
                    "Hindi" to "Hindi"
                )

                LanguageChipGroup(
                    languages = languages,
                    selected = language,
                    onSelect = onLanguageChange
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Task
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 240)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_transcription_task)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TaskChip(
                        label = uiText(R.string.ui_transcribe),
                        isSelected = task == "transcribe",
                        onClick = { onTaskChange("transcribe") },
                        modifier = Modifier.weight(1f)
                    )
                    TaskChip(
                        label = uiText(R.string.ui_translate),
                        isSelected = task == "translate",
                        onClick = { onTaskChange("translate") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (task == "translate") uiText(R.string.ui_audio_english_text) else uiText(R.string.ui_audio_same_language_text),
                    fontSize = 10.sp,
                    color = WarmGray
                )
            }

        }

        Spacer(Modifier.height(14.dp))

        // Assistant Settings
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 280)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_assistant_power_button)) {
                Text(
                    uiText(R.string.ui_stop_recording),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WarmGray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TaskChip(
                        label = uiText(R.string.ui_mode_silence),
                        isSelected = assistantStopMode == SettingsDataStore.ASSISTANT_STOP_SILENCE,
                        onClick = { onAssistantStopModeChange(SettingsDataStore.ASSISTANT_STOP_SILENCE) },
                        modifier = Modifier.weight(1f)
                    )
                    TaskChip(
                        label = uiText(R.string.ui_mode_manual),
                        isSelected = assistantStopMode == SettingsDataStore.ASSISTANT_STOP_MANUAL,
                        onClick = { onAssistantStopModeChange(SettingsDataStore.ASSISTANT_STOP_MANUAL) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (assistantStopMode) {
                        SettingsDataStore.ASSISTANT_STOP_MANUAL -> uiText(R.string.ui_press_power_button_again_to_stop_transcribe)
                        else -> uiText(R.string.ui_silence_stop, silenceTimeoutMs / 1000.0)
                    },
                    fontSize = 10.sp,
                    color = if (assistantStopMode == SettingsDataStore.ASSISTANT_STOP_MANUAL) Copper else WarmGray
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    uiText(R.string.ui_animation_style),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WarmGray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TaskChip(
                        label = uiText(R.string.ui_mode_snake),
                        isSelected = assistantAnimationMode == SettingsDataStore.ANIMATION_MODE_SNAKE,
                        onClick = { onAssistantAnimationModeChange(SettingsDataStore.ANIMATION_MODE_SNAKE) },
                        modifier = Modifier.weight(1f)
                    )
                    TaskChip(
                        label = uiText(R.string.ui_mode_tree),
                        isSelected = assistantAnimationMode == SettingsDataStore.ANIMATION_MODE_TREE,
                        onClick = { onAssistantAnimationModeChange(SettingsDataStore.ANIMATION_MODE_TREE) },
                        modifier = Modifier.weight(1f)
                    )
                    TaskChip(
                        label = uiText(R.string.ui_mode_card),
                        isSelected = assistantAnimationMode == SettingsDataStore.ANIMATION_MODE_CARD,
                        onClick = { onAssistantAnimationModeChange(SettingsDataStore.ANIMATION_MODE_CARD) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (assistantAnimationMode) {
                        SettingsDataStore.ANIMATION_MODE_TREE -> uiText(R.string.ui_organic_amber_branches_growing_from_the_power_button)
                        SettingsDataStore.ANIMATION_MODE_CARD -> uiText(R.string.ui_siri_style_card_expanding_from_camera_punch_hole)
                        else -> uiText(R.string.ui_classic_traveling_light_snake_genie_effect)
                    },
                    fontSize = 10.sp,
                    color = Amber.copy(alpha = 0.7f)
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    uiText(R.string.ui_animation_speed, assistantAnimationSpeed),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WarmGray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Slider(
                    value = assistantAnimationSpeed,
                    onValueChange = onAssistantAnimationSpeedChange,
                    valueRange = 0.5f..3.0f,
                    steps = 5,
                    colors = SliderDefaults.colors(
                        thumbColor = Amber,
                        activeTrackColor = Amber,
                        inactiveTrackColor = WarmGray.copy(alpha = 0.3f)
                    )
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Hardware Buttons
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 320)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_hardware_buttons)) {
                // Volume PTT
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = volumePttEnabled,
                            onValueChange = onVolumePttEnabledChange,
                            role = Role.Switch
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(uiText(R.string.ui_volume_down_ptt), color = WarmWhite, fontSize = 14.sp)
                        Text(
                            uiText(R.string.ui_hold_volume_down_to_record_release_to_stop),
                            color = MutedGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = volumePttEnabled,
                        onCheckedChange = onVolumePttEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.5f)
                        )
                    )
                }

                if (volumePttEnabled) {
                    Spacer(Modifier.height(12.dp))

                    Text(uiText(R.string.ui_select_ptt_button), color = WarmWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("25" to uiText(R.string.ui_vol_down), "24" to uiText(R.string.ui_vol_up)).forEach { (code, label) ->
                            val isSelected = volumePttKeyCode == code
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onVolumePttKeyCodeChange(code) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Amber else DarkSlate,
                                border = BorderStroke(1.dp, if (isSelected) Amber else MutedGray.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    textAlign = TextAlign.Center,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) DeepNoir else WarmWhite
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        uiText(R.string.ui_requires_accessibility_permission),
                        color = Amber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Voice Keyboard — double-tap power → live type into focused field
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MutedGray.copy(alpha = 0.3f))
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = voiceKeyboardMode,
                            onValueChange = onVoiceKeyboardModeChange,
                            role = Role.Switch
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(uiText(R.string.ui_voice_keyboard_mode), color = WarmWhite, fontSize = 14.sp)
                        Text(
                            uiText(R.string.ui_dictate_live_into_any_text_field_triggers_double_tap_volume_or_samsung_side_key_below),
                            color = MutedGray,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = voiceKeyboardMode,
                        onCheckedChange = onVoiceKeyboardModeChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DeepNoir,
                            checkedTrackColor = Amber,
                            uncheckedThumbColor = MutedGray,
                            uncheckedTrackColor = DarkSlate
                        )
                    )
                }

                if (voiceKeyboardMode) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = DarkSlate,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            uiText(R.string.neo_keyboard_setup),
                            color = WarmWhite,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(10.dp),
                            lineHeight = 14.sp
                        )
                    }
                }

                // Digital Assistant Overlay (Power / Side button long-press)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MutedGray.copy(alpha = 0.3f))
                Spacer(Modifier.height(12.dp))

                Text(uiText(R.string.ui_digital_assistant_overlay), color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    uiText(R.string.ui_long_press_power_side_button_to_open_whisperflow_as_your_voice_assistant),
                    color = MutedGray,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))

                val context = LocalContext.current
                val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else true

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (hasOverlay) uiText(R.string.ui_overlay_permission_granted) else uiText(R.string.ui_overlay_permission_required),
                            color = if (hasOverlay) Emerald else CoralRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            uiText(R.string.ui_needed_for_the_assistant_ui_to_appear_over_other_apps),
                            color = MutedGray,
                            fontSize = 10.sp
                        )
                    }
                    if (!hasOverlay) {
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Amber)
                        ) {
                            Text(uiText(R.string.ui_grant), color = DeepNoir, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Surface(
                    color = DarkSlate,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Samsung (S24 etc):  Settings → Advanced features → Side key → Press & hold → Open app → WhisperFlow\n\nThis is required — the system setting alone is not enough on One UI.",
                        color = WarmWhite,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(10.dp),
                        lineHeight = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Sound & Haptics
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 320)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_sound_haptics)) {
                // Sound Feedback Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = soundEnabled,
                            onValueChange = onSoundEnabledChange,
                            role = Role.Switch
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(uiText(R.string.ui_sound_feedback), color = WarmWhite, fontSize = 14.sp)
                        Text(
                            uiText(R.string.ui_play_warm_tones_on_start_stop_success_error),
                            color = MutedGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = soundEnabled,
                        onCheckedChange = onSoundEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MutedGray.copy(alpha = 0.3f))
                Spacer(Modifier.height(12.dp))

                // Vibration Feedback Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = vibrationEnabled,
                            onValueChange = onVibrationEnabledChange,
                            role = Role.Switch
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(uiText(R.string.ui_vibration_feedback), color = WarmWhite, fontSize = 14.sp)
                        Text(
                            uiText(R.string.ui_play_physical_vibration_haptic_cues),
                            color = MutedGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = vibrationEnabled,
                        onCheckedChange = onVibrationEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.5f)
                        )
                    )
                }

                if (vibrationEnabled) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MutedGray.copy(alpha = 0.3f))
                    Spacer(Modifier.height(12.dp))

                    Text(
                        uiText(R.string.ui_vibration_intensity, (vibrationIntensity * 100).toInt()),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = WarmGray,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Slider(
                        value = vibrationIntensity,
                        onValueChange = onVibrationIntensityChange,
                        valueRange = 0.1f..1.0f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = Amber,
                            activeTrackColor = Amber,
                            inactiveTrackColor = WarmGray.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Transcription Tuning
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 320)) + slideInVertically(initialOffsetY = { 20 })
        ) {
            SettingsCard(title = uiText(R.string.ui_transcription_tuning)) {
                // Chunk Interval
                Text(
                    uiText(R.string.ui_chunk_interval, chunkIntervalMs / 1000.0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WarmWhite
                )
                Slider(
                    value = chunkIntervalMs.toFloat(),
                    onValueChange = { onChunkIntervalChange(it.toLong()) },
                    valueRange = 1000f..4000f,
                    steps = 14,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Amber,
                        activeTrackColor = Amber,
                        inactiveTrackColor = MutedGray
                    )
                )

                Spacer(Modifier.height(8.dp))

                // Silence Timeout (assistant)
                Text(
                    uiText(R.string.ui_silence_timeout, silenceTimeoutMs / 1000.0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WarmWhite
                )
                Slider(
                    value = silenceTimeoutMs.toFloat(),
                    onValueChange = { onSilenceTimeoutChange(it.toLong()) },
                    valueRange = 1000f..4000f,
                    steps = 14,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Amber,
                        activeTrackColor = Amber,
                        inactiveTrackColor = MutedGray
                    )
                )

                Spacer(Modifier.height(8.dp))

                // Hallucination Filter
                Text(
                    uiText(R.string.ui_hallucination_filter, hallucinationThreshold),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WarmWhite
                )
                Slider(
                    value = hallucinationThreshold,
                    onValueChange = { onHallucinationThresholdChange(Math.round(it * 100f) / 100f) },
                    valueRange = 0.05f..0.30f,
                    steps = 24,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Amber,
                        activeTrackColor = Amber,
                        inactiveTrackColor = MutedGray
                    )
                )
                Text(
                    uiText(R.string.ui_lower_stricter_filtering_of_hallucinated_phrases),
                    fontSize = 10.sp,
                    color = WarmGray
                )

                Spacer(Modifier.height(8.dp))

                // Toast Template
                Text(
                    uiText(R.string.ui_toast_template),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WarmWhite,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                StyledTextField(
                    value = toastTemplate,
                    onValueChange = onToastTemplateChange,
                    placeholder = uiText(R.string.ui_text_is_replaced_with_transcription)
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MutedGray.copy(alpha = 0.3f))
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = ramblerEnabled,
                            onValueChange = onRamblerEnabledChange,
                            role = Role.Switch
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(uiText(R.string.ui_rambler_voice_dictation), color = WarmWhite, fontSize = 14.sp)
                        Text(
                            uiText(R.string.ui_cleanup_filler_words_and_self_corrections_using_llm_gateway),
                            color = MutedGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = ramblerEnabled,
                        onCheckedChange = onRamblerEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Save
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 400))
        ) {
            PrimaryButton(text = uiText(R.string.ui_save_settings), onClick = onSave)
        }

        Spacer(Modifier.height(14.dp))

        // Info
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 400))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSlate.copy(alpha = 0.4f))
                    .padding(14.dp)
            ) {
                Text(
                    uiText(R.string.ui_connection_info),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = WarmGray,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(6.dp))
                InfoRow(uiText(R.string.ui_server_url), serverUrl.ifEmpty { uiText(R.string.ui_not_configured) })
                InfoRow(uiText(R.string.ui_core), uiText(R.string.ui_your_whisper_server))
                InfoRow(uiText(R.string.ui_model), "whisper-large-v3-turbo")
                InfoRow("Gradio", "6.5.1")
                InfoRow("API", "/gradio_api/call/transcribe")
            }
        }

        SettingsCard(title = androidx.compose.ui.res.stringResource(R.string.advanced_section)) {
            TextButton(onClick = {
                val current = com.pimax.whisperflow.sync.UiLanguage.code(languageContext)
                com.pimax.whisperflow.sync.UiLanguage.set(languageContext, if (current == "ru") "en" else "ru")
                (languageContext as? android.app.Activity)?.recreate()
            }, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(R.string.interface_language), color = Amber)
            }
            TextButton(onClick = onOpenConnections, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(R.string.external_connections), color = Amber)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ═══════════════════════════════════════════════════════════════
// 🧩 SHARED COMPONENTS
// ═══════════════════════════════════════════════════════════════

@Composable
fun SmallActionButton(text: String, color: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        label = "sab"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun BottomNavBar(tabs: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val icons = listOf(Icons.Default.Home, Icons.Default.Refresh, Icons.Default.Favorite, Icons.Default.Settings)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, DeepNoir.copy(alpha = 0.95f), DeepNoir)
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val color by animateColorAsState(
                targetValue = if (selected) Amber else WarmGray,
                label = "tc"
            )
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.04f else 1f,
                label = "ts"
            )

            Column(
                modifier = Modifier
                    .scale(scale)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(index) }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icons[index], contentDescription = tab, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(3.dp))
                Text(
                    tab,
                    fontSize = 8.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = color,
                    letterSpacing = 1.5.sp
                )
                if (selected) {
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Amber)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurface)
            .border(1.dp, MutedGray.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .padding(18.dp)
    ) {
        Text(
            title,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = WarmGray,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun StyledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder, color = MutedGray, fontSize = 13.sp) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber.copy(alpha = 0.45f),
            unfocusedBorderColor = MutedGray.copy(alpha = 0.18f),
            cursorColor = Amber,
            focusedTextColor = WarmWhite,
            unfocusedTextColor = WarmWhite
        ),
        shape = RoundedCornerShape(10.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true
    )
}

@Composable
fun LanguageChipGroup(
    languages: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val rows = languages.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { (code, _) ->
                    val isSelected = code == selected
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) Amber.copy(alpha = 0.12f) else Color.Transparent,
                        label = "lb"
                    )
                    val borderColor by animateColorAsState(
                        targetValue = if (isSelected) Amber.copy(alpha = 0.45f) else MutedGray.copy(alpha = 0.12f),
                        label = "lbr"
                    )
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) Amber else WarmGray,
                        label = "lt"
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(bgColor)
                            .border(1.dp, borderColor, RoundedCornerShape(7.dp))
                            .clickable { onSelect(code) }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            if (code == "Auto") "AUTO" else code.take(3).uppercase(),
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TaskChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) Amber.copy(alpha = 0.1f) else Color.Transparent,
        label = "tBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) Amber.copy(alpha = 0.45f) else MutedGray.copy(alpha = 0.12f),
        label = "tBr"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Amber else WarmGray,
        label = "tTx"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "pb"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(colors = listOf(Amber, Copper)))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            color = DeepNoir,
            letterSpacing = 3.sp
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = WarmGray)
        Text(value, fontSize = 11.sp, color = WarmWhite.copy(alpha = 0.65f), fontWeight = FontWeight.Medium)
    }
}
