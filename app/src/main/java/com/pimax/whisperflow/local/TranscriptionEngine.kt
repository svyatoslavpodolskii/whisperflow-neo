package com.pimax.whisperflow.local

import android.content.Context
import android.util.Log
import com.pimax.whisperflow.WhisperApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unified transcription engine that supports both GPU server and local inference.
 *
 * Provides a consistent API regardless of whether transcription happens remotely
 * on the GPU server or locally using TensorFlow Lite.
 *
 * Usage:
 * ```
 * val engine = TranscriptionEngine(context)
 * engine.setMode(TranscriptionMode.LOCAL)
 * engine.initializeLocal(ModelManager.WhisperModel.TINY)
 *
 * val result = engine.transcribe(audioSamples)
 * ```
 */
class TranscriptionEngine(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "TranscriptionEngine"
    }

    /**
     * Transcription mode.
     */
    enum class TranscriptionMode {
        /** Use remote GPU server via Gradio API */
        GPU_SERVER,
        /** Use LLM Gateway via Groq STT */
        LLM_GATEWAY,
        /** Use local TFLite model on device */
        LOCAL
    }

    /**
     * Engine state.
     */
    sealed class EngineState {
        object Uninitialized : EngineState()
        object Initializing : EngineState()
        object Ready : EngineState()
        data class Error(val message: String) : EngineState()
    }

    // Current mode
    private var _mode = TranscriptionMode.GPU_SERVER
    val mode: TranscriptionMode get() = _mode

    // State tracking
    private val _state = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // Inference activity tracking - counts active inference operations
    private val _activeInferences = AtomicInteger(0)
    private val _isInferenceActive = MutableStateFlow(false)
    val isInferenceActive: StateFlow<Boolean> = _isInferenceActive.asStateFlow()

    // Last inference timing for activity display
    private val _lastInferenceTimeMs = MutableStateFlow(0L)
    val lastInferenceTimeMs: StateFlow<Long> = _lastInferenceTimeMs.asStateFlow()

    // Local engine (lazily initialized)
    private var localEngine: LocalWhisperEngine? = null
    private var currentLocalModel: ModelManager.WhisperModel? = null

    // Model manager for local inference
    val modelManager: ModelManager by lazy { ModelManager(context) }

    // GPU server settings
    private var serverUrl: String = ""
    private var serverLanguage: String = "en"
    private var gatewayUrl: String = ""

    /**
     * Set the transcription mode.
     */
    fun setMode(mode: TranscriptionMode) {
        _mode = mode
        Log.d(TAG, "Mode set to: $mode")

        // Update state based on mode
        _state.value = when (mode) {
            TranscriptionMode.GPU_SERVER, TranscriptionMode.LLM_GATEWAY -> EngineState.Ready
            TranscriptionMode.LOCAL -> {
                if (localEngine?.isReady() == true) {
                    EngineState.Ready
                } else {
                    EngineState.Uninitialized
                }
            }
        }
    }

    /**
     * Configure GPU server settings.
     */
    fun configureServer(url: String, language: String = "en", gatewayUrl: String? = null) {
        serverUrl = url
        serverLanguage = language
        if (gatewayUrl != null) this.gatewayUrl = gatewayUrl
        Log.d(TAG, "Server configured: url=$url, language=$language, gateway=$gatewayUrl")
    }

    /**
     * Initialize local engine with a specific model.
     *
     * @param model The Whisper model to use
     * @return Result indicating success or failure
     */
    suspend fun initializeLocal(model: ModelManager.WhisperModel): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                _state.value = EngineState.Initializing
                Log.d(TAG, "Initializing local engine with ${model.displayName}")

                // Check if model is installed
                if (!modelManager.isModelInstalled(model)) {
                    val error = "Model ${model.displayName} is not downloaded"
                    _state.value = EngineState.Error(error)
                    return@withContext Result.failure(IllegalStateException(error))
                }

                // Check if vocab is installed
                if (!modelManager.isVocabInstalled()) {
                    val error = "Vocabulary file is not downloaded"
                    _state.value = EngineState.Error(error)
                    return@withContext Result.failure(IllegalStateException(error))
                }

                // Close existing engine if different model
                if (currentLocalModel != model) {
                    localEngine?.close()
                    localEngine = null
                }

                // Create new engine if needed
                if (localEngine == null) {
                    localEngine = LocalWhisperEngine.create(
                        context = context,
                        modelPath = modelManager.getModelFile(model).absolutePath,
                        vocabPath = modelManager.getVocabFile().absolutePath,
                        isMultilingual = true
                    )
                    currentLocalModel = model
                }

                _state.value = EngineState.Ready
                Log.d(TAG, "Local engine initialized successfully")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize local engine: ${e.message}", e)
                _state.value = EngineState.Error(e.message ?: "Unknown error")
                Result.failure(e)
            }
        }
    }

    /**
     * Transcribe audio samples.
     *
     * Routes to either GPU server or local engine based on current mode.
     *
     * @param samples PCM float samples (16kHz, mono, normalized to [-1, 1])
     * @param language Optional language code (for GPU server) or language token (for local)
     * @return TranscriptionResult with text and metadata
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String? = null
    ): TranscriptionResult {
        markInferenceStart()
        return try {
            when (_mode) {
                TranscriptionMode.GPU_SERVER -> transcribeGpu(samples, language ?: serverLanguage)
                TranscriptionMode.LLM_GATEWAY -> transcribeGateway(samples, language ?: serverLanguage)
                TranscriptionMode.LOCAL -> transcribeLocal(samples, language ?: serverLanguage)
            }
        } finally {
            markInferenceEnd()
        }
    }

    /**
     * Transcribe audio bytes (WAV format).
     *
     * Only supported for GPU server mode (sends raw bytes).
     * For local mode, convert to float samples first.
     */
    suspend fun transcribeWav(
        wavBytes: ByteArray,
        language: String? = null
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        markInferenceStart()
        try {
            when (_mode) {
                TranscriptionMode.GPU_SERVER -> {
                val startTime = System.currentTimeMillis()

                // Write bytes to temp file for WhisperApi
                val tempFile = java.io.File.createTempFile("whisper_", ".wav", context.cacheDir)
                tempFile.writeBytes(wavBytes)

                try {
                    val api = WhisperApi(serverUrl)
                    val result = api.transcribe(tempFile, language ?: serverLanguage, "transcribe")
                    val duration = System.currentTimeMillis() - startTime

                    TranscriptionResult(
                        text = result.text,
                        language = result.language ?: (language ?: serverLanguage),
                        mode = TranscriptionMode.GPU_SERVER,
                        processingTimeMs = duration,
                        modelName = "GPU Server"
                    )
                } finally {
                    tempFile.delete()
                }
            }
            TranscriptionMode.LLM_GATEWAY -> {
                Log.d("TranscriptionEngine", "🚀 transcribeWav: LLM_GATEWAY mode using $gatewayUrl")
                val startTime = System.currentTimeMillis()
                val tempFile = java.io.File.createTempFile("whisper_", ".wav", context.cacheDir)
                tempFile.writeBytes(wavBytes)
                try {
                    val api = WhisperApi(serverUrl)
                    val result = api.transcribeViaGateway(tempFile, gatewayUrl, "whisper-large-v3-turbo", language ?: serverLanguage)
                    val duration = System.currentTimeMillis() - startTime
                    Log.d("TranscriptionEngine", "✅ LLM_GATEWAY result in ${duration}ms: ${result.text.take(50)}...")
                    TranscriptionResult(
                        text = result.text,
                        language = result.language ?: (language ?: serverLanguage),
                        mode = TranscriptionMode.LLM_GATEWAY,
                        processingTimeMs = duration,
                        modelName = "LLM Gateway (Groq)"
                    )
                } finally {
                    tempFile.delete()
                }
            }
            TranscriptionMode.LOCAL -> {
                Log.d("TranscriptionEngine", "🚀 transcribeWav: LOCAL mode")
                val samples = wavBytesToFloatSamples(wavBytes)
                transcribeLocal(samples, language ?: serverLanguage)
            }
        }
        } finally {
            markInferenceEnd()
        }
    }

    private suspend fun transcribeGpu(
        samples: FloatArray,
        language: String
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Convert float samples to WAV bytes and write to temp file
        val wavBytes = floatSamplesToWav(samples)
        val tempFile = java.io.File.createTempFile("whisper_", ".wav", context.cacheDir)
        tempFile.writeBytes(wavBytes)

        try {
            val api = WhisperApi(serverUrl)
            val result = api.transcribe(tempFile, language, "transcribe")
            val duration = System.currentTimeMillis() - startTime

            TranscriptionResult(
                text = result.text,
                language = result.language ?: language,
                mode = TranscriptionMode.GPU_SERVER,
                processingTimeMs = duration,
                modelName = "GPU Server"
            )
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun transcribeGateway(
        samples: FloatArray,
        language: String
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val wavBytes = floatSamplesToWav(samples)
        val tempFile = java.io.File.createTempFile("whisper_", ".wav", context.cacheDir)
        tempFile.writeBytes(wavBytes)

        try {
            val api = WhisperApi(serverUrl)
            val result = api.transcribeViaGateway(tempFile, gatewayUrl, "whisper-large-v3-turbo", language)
            val duration = System.currentTimeMillis() - startTime

            TranscriptionResult(
                text = result.text,
                language = result.language ?: language,
                mode = TranscriptionMode.LLM_GATEWAY,
                processingTimeMs = duration,
                modelName = "LLM Gateway (Groq)"
            )
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun transcribeLocal(samples: FloatArray, language: String = "en"): TranscriptionResult {
        val engine = localEngine
            ?: throw IllegalStateException("Local engine not initialized. Call initializeLocal() first.")

        // Map language code (e.g., "English", "en") to a Whisper language token ID.
        // This forces the model to transcribe in the specified language instead of
        // auto-detecting, which is unreliable on smaller models (Tiny/Base) and
        // causes language mixing.
        val langCode = mapSettingsLanguage(language)
        val langToken = if (langCode != null) engine.vocab.languageTokenFor(langCode) else null
        if (langToken != null) {
            Log.d(TAG, "Forcing local language: $language → code=$langCode → token=$langToken")
        } else {
            Log.d(TAG, "Local auto-detect mode (no forced language)")
        }

        val result = engine.transcribe(samples, langToken)

        return TranscriptionResult(
            text = result.text,
            language = result.language ?: langCode,
            mode = TranscriptionMode.LOCAL,
            processingTimeMs = result.processingTimeMs,
            modelName = currentLocalModel?.displayName ?: "Unknown"
        )
    }

    /**
     * Map a settings language string (e.g., "English", "Auto", "en") to a
     * two-letter ISO code that WhisperVocab understands. Returns null for "Auto".
     */
    private fun mapSettingsLanguage(lang: String): String? {
        return when (lang.lowercase().trim()) {
            "auto" -> null
            "en", "english" -> "en"
            "de", "german" -> "de"
            "zh", "chinese" -> "zh"
            "es", "spanish" -> "es"
            "fr", "french" -> "fr"
            "it", "italian" -> "it"
            "pt", "portuguese" -> "pt"
            "ru", "russian" -> "ru"
            "ja", "japanese" -> "ja"
            "ko", "korean" -> "ko"
            "ar", "arabic" -> "ar"
            "hi", "hindi" -> "hi"
            else -> {
                // If it's already a 2-letter code, use it directly
                if (lang.length == 2) lang.lowercase() else null
            }
        }
    }

    /**
     * Translate audio to English.
     *
     * @param samples PCM float samples (16kHz, mono, normalized to [-1, 1])
     * @return TranscriptionResult with translated text
     */
    suspend fun translate(samples: FloatArray): TranscriptionResult {
        return when (_mode) {
            TranscriptionMode.GPU_SERVER, TranscriptionMode.LLM_GATEWAY -> {
                // Remote modes don't support translation directly here, transcribe in English
                if (_mode == TranscriptionMode.GPU_SERVER) transcribeGpu(samples, "en")
                else transcribeGateway(samples, "en")
            }
            TranscriptionMode.LOCAL -> {
                val engine = localEngine
                    ?: throw IllegalStateException("Local engine not initialized")

                val result = engine.translate(samples)

                TranscriptionResult(
                    text = result.text,
                    language = "en",
                    mode = TranscriptionMode.LOCAL,
                    processingTimeMs = result.processingTimeMs,
                    modelName = currentLocalModel?.displayName ?: "Unknown"
                )
            }
        }
    }

    /**
     * Cancel ongoing transcription (local mode only).
     */
    fun cancel() {
        localEngine?.cancel()
    }

    /**
     * Check if engine is ready for transcription.
     */
    fun isReady(): Boolean {
        return when (_mode) {
            TranscriptionMode.GPU_SERVER, TranscriptionMode.LLM_GATEWAY -> true // Remote modes always ready
            TranscriptionMode.LOCAL -> localEngine?.isReady() == true
        }
    }

    /**
     * Get current model info (local mode only).
     */
    fun getLocalModelInfo(): LocalWhisperEngine.ModelInfo? {
        return localEngine?.getModelInfo()
    }

    /**
     * Mark inference as started (for activity tracking).
     */
    private fun markInferenceStart() {
        val count = _activeInferences.incrementAndGet()
        _isInferenceActive.value = count > 0
        Log.d(TAG, "Inference started (active: $count)")
    }

    /**
     * Mark inference as ended (for activity tracking).
     */
    private fun markInferenceEnd() {
        val count = _activeInferences.decrementAndGet()
        _isInferenceActive.value = count > 0
        _lastInferenceTimeMs.value = System.currentTimeMillis()
        Log.d(TAG, "Inference ended (active: $count)")
    }

    /**
     * Get current inference activity level (0.0 to 1.0).
     * Used for visual activity indicators.
     */
    fun getActivityLevel(): Float {
        return if (_isInferenceActive.value) {
            0.7f + (Math.random() * 0.25f).toFloat() // 70-95% when active
        } else {
            0.05f + (Math.random() * 0.1f).toFloat() // 5-15% when idle
        }
    }

    override fun close() {
        localEngine?.close()
        localEngine = null
        currentLocalModel = null
        _activeInferences.set(0)
        _isInferenceActive.value = false
        _state.value = EngineState.Uninitialized
        Log.d(TAG, "Engine closed")
    }

    /**
     * Convert float samples to WAV byte array.
     */
    private fun floatSamplesToWav(samples: FloatArray): ByteArray {
        val sampleRate = 16000
        val bitsPerSample = 16
        val numChannels = 1
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = samples.size * 2 // 16-bit samples

        val buffer = java.nio.ByteBuffer.allocate(44 + dataSize)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Chunk size
        buffer.putShort(1) // Audio format (PCM)
        buffer.putShort(numChannels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        // Convert float samples to 16-bit PCM
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            val pcmValue = (clamped * 32767).toInt().toShort()
            buffer.putShort(pcmValue)
        }

        return buffer.array()
    }

    /**
     * Convert WAV bytes to float samples.
     */
    private fun wavBytesToFloatSamples(wavBytes: ByteArray): FloatArray {
        val buffer = java.nio.ByteBuffer.wrap(wavBytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // Skip WAV header (44 bytes)
        buffer.position(44)

        val numSamples = (wavBytes.size - 44) / 2 // 16-bit samples
        val samples = FloatArray(numSamples)

        for (i in 0 until numSamples) {
            if (buffer.remaining() >= 2) {
                val pcmValue = buffer.short
                samples[i] = pcmValue / 32768f
            }
        }

        return samples
    }

    /**
     * Transcription result.
     */
    data class TranscriptionResult(
        val text: String,
        val language: String?,
        val mode: TranscriptionMode,
        val processingTimeMs: Long,
        val modelName: String
    )
}
