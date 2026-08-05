package com.pimax.whisperflow.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local Whisper inference engine using TensorFlow Lite.
 *
 * Wraps a TFLite Whisper model and provides transcription/translation capabilities.
 * Uses MelSpectrogram for audio preprocessing and WhisperVocab for token decoding.
 *
 * Usage:
 * ```
 * val engine = LocalWhisperEngine.create(context, modelPath, vocabPath, isMultilingual = true)
 * val result = engine.transcribe(audioSamples)
 * engine.close()
 * ```
 */
class LocalWhisperEngine private constructor(
    private val interpreter: Interpreter,
    internal val vocab: WhisperVocab,
    private val isMultilingual: Boolean
) : Closeable {

    // Thread safety - TFLite interpreter is NOT thread-safe
    private val inferenceMutex = Mutex()
    private val isRunning = AtomicBoolean(false)

    companion object {
        private const val TAG = "LocalWhisperEngine"

        /**
         * Create a LocalWhisperEngine instance.
         *
         * @param context Android context (for accessing files)
         * @param modelPath Path to the .tflite model file
         * @param vocabPath Path to the filters_vocab_*.bin file
         * @param isMultilingual Whether the model is multilingual (default: true)
         * @param numThreads Number of threads for inference (default: CPU cores)
         * @return Initialized LocalWhisperEngine
         */
        suspend fun create(
            context: Context,
            modelPath: String,
            vocabPath: String,
            isMultilingual: Boolean = true,
            numThreads: Int = Runtime.getRuntime().availableProcessors()
        ): LocalWhisperEngine = withContext(Dispatchers.IO) {
            Log.d(TAG, "Loading model from: $modelPath")
            Log.d(TAG, "Loading vocab from: $vocabPath")

            // Load TFLite model
            val modelFile = File(modelPath)
            val modelBuffer = loadModelFile(modelFile)

            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                // XNNPACK doesn't work with dynamic tensors in Whisper models
                setUseXNNPACK(false)
                setCancellable(true)
            }

            val interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "Model loaded. Signatures: ${interpreter.signatureKeys.joinToString()}")

            // Load vocabulary and filters
            val vocabFile = File(vocabPath)
            val vocab = vocabFile.inputStream().use { stream ->
                WhisperVocab.load(stream, isMultilingual)
            }
            Log.d(TAG, "Vocab loaded. Filters: ${vocab.filters.nMel}x${vocab.filters.nFft}")

            LocalWhisperEngine(interpreter, vocab, isMultilingual)
        }

        /**
         * Create from asset files (for bundled models).
         */
        suspend fun createFromAssets(
            context: Context,
            modelAssetName: String,
            vocabAssetName: String,
            isMultilingual: Boolean = true,
            numThreads: Int = Runtime.getRuntime().availableProcessors()
        ): LocalWhisperEngine = withContext(Dispatchers.IO) {
            // Copy assets to cache directory
            val modelFile = File(context.cacheDir, modelAssetName)
            val vocabFile = File(context.cacheDir, vocabAssetName)

            if (!modelFile.exists()) {
                context.assets.open(modelAssetName).use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (!vocabFile.exists()) {
                context.assets.open(vocabAssetName).use { input ->
                    vocabFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            create(context, modelFile.absolutePath, vocabFile.absolutePath, isMultilingual, numThreads)
        }

        private fun loadModelFile(file: File): ByteBuffer {
            FileInputStream(file).use { fis ->
                val channel = fis.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        }
    }

    // Available signatures in the model
    private val hasTranscribeSignature: Boolean by lazy {
        "serving_transcribe" in interpreter.signatureKeys
    }

    private val hasTranslateSignature: Boolean by lazy {
        "serving_translate" in interpreter.signatureKeys
    }

    private val hasLangSignature: Boolean by lazy {
        "serving_transcribe_lang" in interpreter.signatureKeys
    }

    /**
     * Transcribe audio samples to text.
     *
     * @param samples PCM float samples (16kHz, mono, normalized to [-1, 1])
     * @param language Optional language token ID for forced language (multilingual only)
     * @return TranscriptionResult containing text and metadata
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: Int? = null
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        // Use mutex to prevent concurrent TFLite access (causes native crashes)
        inferenceMutex.withLock {
            runInference(samples, Task.TRANSCRIBE, language)
        }
    }

    /**
     * Translate audio to English text.
     *
     * @param samples PCM float samples (16kHz, mono, normalized to [-1, 1])
     * @return TranscriptionResult containing translated text
     */
    suspend fun translate(
        samples: FloatArray
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        // Use mutex to prevent concurrent TFLite access
        inferenceMutex.withLock {
            runInference(samples, Task.TRANSLATE, null)
        }
    }

    /**
     * Synchronous transcription (blocks current thread).
     * WARNING: Not thread-safe - caller must ensure single-threaded access.
     */
    @Synchronized
    fun transcribeSync(
        samples: FloatArray,
        language: Int? = null
    ): TranscriptionResult {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Skipping concurrent transcribeSync call")
            return TranscriptionResult("", null, Task.TRANSCRIBE, 0L)
        }
        return try {
            runInferenceSync(samples, Task.TRANSCRIBE, language)
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * Synchronous translation (blocks current thread).
     * WARNING: Not thread-safe - caller must ensure single-threaded access.
     */
    @Synchronized
    fun translateSync(samples: FloatArray): TranscriptionResult {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Skipping concurrent translateSync call")
            return TranscriptionResult("", null, Task.TRANSLATE, 0L)
        }
        return try {
            runInferenceSync(samples, Task.TRANSLATE, null)
        } finally {
            isRunning.set(false)
        }
    }

    private suspend fun runInference(
        samples: FloatArray,
        task: Task,
        languageToken: Int?
    ): TranscriptionResult {
        val startTime = System.currentTimeMillis()

        // Compute mel spectrogram
        Log.d(TAG, "Computing mel spectrogram...")
        val melSpectrogram = MelSpectrogram.compute(samples, vocab.filters)
        val melTime = System.currentTimeMillis()
        Log.d(TAG, "Mel spectrogram computed in ${melTime - startTime}ms")

        // Run inference
        Log.d(TAG, "Running inference...")
        val tokens = runModel(melSpectrogram, task, languageToken)
        val inferenceTime = System.currentTimeMillis()
        Log.d(TAG, "Inference completed in ${inferenceTime - melTime}ms")

        // Decode tokens
        val result = vocab.decodeWithMetadata(tokens)
        val totalTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Total time: ${totalTime}ms, text: '${result.text.take(50)}...'")

        return TranscriptionResult(
            text = result.text.trim(),
            language = result.language,
            task = when (result.task) {
                WhisperVocab.Task.TRANSCRIBE -> Task.TRANSCRIBE
                WhisperVocab.Task.TRANSLATE -> Task.TRANSLATE
                null -> task
            },
            processingTimeMs = totalTime
        )
    }

    private fun runInferenceSync(
        samples: FloatArray,
        task: Task,
        languageToken: Int?
    ): TranscriptionResult {
        val startTime = System.currentTimeMillis()

        // Compute mel spectrogram (sync version)
        Log.d(TAG, "Computing mel spectrogram (sync)...")
        val melSpectrogram = MelSpectrogram.computeSync(samples, vocab.filters)
        val melTime = System.currentTimeMillis()
        Log.d(TAG, "Mel spectrogram computed in ${melTime - startTime}ms")

        // Run inference
        Log.d(TAG, "Running inference...")
        val tokens = runModel(melSpectrogram, task, languageToken)
        val inferenceTime = System.currentTimeMillis()
        Log.d(TAG, "Inference completed in ${inferenceTime - melTime}ms")

        // Decode tokens
        val result = vocab.decodeWithMetadata(tokens)
        val totalTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Total time: ${totalTime}ms, text: '${result.text.take(50)}...'")

        return TranscriptionResult(
            text = result.text.trim(),
            language = result.language,
            task = when (result.task) {
                WhisperVocab.Task.TRANSCRIBE -> Task.TRANSCRIBE
                WhisperVocab.Task.TRANSLATE -> Task.TRANSLATE
                null -> task
            },
            processingTimeMs = totalTime
        )
    }

    private fun runModel(
        melSpectrogram: FloatArray,
        task: Task,
        languageToken: Int?
    ): IntArray {
        // Double-check interpreter is ready
        if (!isReady()) {
            Log.e(TAG, "Interpreter not ready, aborting inference")
            return IntArray(0)
        }

        // Select appropriate signature
        val signatureKey = selectSignature(task, languageToken)
        Log.d(TAG, "Using signature: $signatureKey")

        // Get input/output names for the signature
        val inputNames = interpreter.getSignatureInputs(signatureKey)
        val outputNames = interpreter.getSignatureOutputs(signatureKey)
        Log.d(TAG, "Input names: ${inputNames.toList()}, Output names: ${outputNames.toList()}")

        // Get tensor info from signature runner
        val inputTensor = interpreter.getInputTensorFromSignature(inputNames[0], signatureKey)
        val outputTensor = interpreter.getOutputTensorFromSignature(outputNames[0], signatureKey)

        val inputShape = inputTensor.shape()
        val outputShape = outputTensor.shape()
        Log.d(TAG, "Input shape: ${inputShape.toList()}, Output shape: ${outputShape.toList()}")
        Log.d(TAG, "Mel spectrogram size: ${melSpectrogram.size}")

        // Calculate expected input size (shape: 1 x 80 x 3000 for Whisper)
        val expectedInputElements = inputShape.fold(1) { acc, dim -> acc * dim }
        Log.d(TAG, "Expected input elements: $expectedInputElements, Provided: ${melSpectrogram.size}")

        // Prepare input buffer
        val inputBuffer = ByteBuffer.allocateDirect(expectedInputElements * 4).apply {
            order(ByteOrder.nativeOrder())
            // Copy mel spectrogram, padding with zeros if needed
            for (i in 0 until expectedInputElements) {
                if (i < melSpectrogram.size) {
                    putFloat(melSpectrogram[i])
                } else {
                    putFloat(0f) // Pad with zeros
                }
            }
            rewind()
        }

        // Calculate output size - Whisper outputs token IDs as int32
        // Output shape is [1, max_tokens] for batch size 1
        Log.d(TAG, "Output elements: ${outputShape.toList()}")

        // Allocate output as 2D array to match TFLite tensor shape [1, 451]
        val batchSize = outputShape[0]
        val maxTokens = outputShape[1]
        val outputArray = Array(batchSize) { IntArray(maxTokens) }

        // Run inference with signature
        val inputs = mapOf<String, Any>(inputNames[0] to inputBuffer)
        val outputs = mapOf<String, Any>(outputNames[0] to outputArray)

        try {
            Log.d(TAG, "Running inference on thread: ${Thread.currentThread().name}")
            interpreter.runSignature(inputs, outputs, signatureKey)
            Log.d(TAG, "Inference completed successfully")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Interpreter state error (likely closed or concurrent access): ${e.message}")
            return IntArray(0)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Tensor shape/type error: ${e.message}")
            return IntArray(0)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            return IntArray(0)
        }

        // Extract tokens from first batch (index 0)
        val tokens = outputArray[0]

        // Find actual token count (stop at padding/end token)
        var actualLength = tokens.size
        for (i in tokens.indices) {
            // End of text token is typically 50257 or similar high value
            // Padding is typically 0
            if (tokens[i] == 0 || tokens[i] == 50257) {
                actualLength = i
                break
            }
        }

        Log.d(TAG, "Output tokens (first 20): ${tokens.take(20)}")
        Log.d(TAG, "Actual token length: $actualLength")

        return tokens.copyOf(actualLength)
    }

    private fun selectSignature(task: Task, languageToken: Int?): String {
        return when {
            task == Task.TRANSLATE && hasTranslateSignature -> "serving_translate"
            task == Task.TRANSCRIBE && languageToken != null && hasLangSignature -> "serving_transcribe_lang"
            task == Task.TRANSCRIBE && hasTranscribeSignature -> "serving_transcribe"
            else -> "serving_default"
        }
    }

    /**
     * Cancel any ongoing inference.
     */
    fun cancel() {
        try {
            // Note: setCancelled might throw if interpreter is already closed
            interpreter.setCancelled(true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel: ${e.message}")
        }
    }

    /**
     * Check if engine is initialized and ready.
     */
    fun isReady(): Boolean {
        return try {
            interpreter.inputTensorCount > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get model info for display purposes.
     */
    fun getModelInfo(): ModelInfo {
        val inputShape = interpreter.getInputTensor(0).shape()
        val outputShape = interpreter.getOutputTensor(0).shape()

        return ModelInfo(
            inputShape = inputShape.toList(),
            outputShape = outputShape.toList(),
            signatures = interpreter.signatureKeys.toList(),
            isMultilingual = isMultilingual
        )
    }

    override fun close() {
        try {
            interpreter.close()
            Log.d(TAG, "Engine closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing engine: ${e.message}")
        }
    }

    /**
     * Transcription task type.
     */
    enum class Task {
        TRANSCRIBE,
        TRANSLATE
    }

    /**
     * Result of transcription/translation.
     */
    data class TranscriptionResult(
        val text: String,
        val language: String?,
        val task: Task,
        val processingTimeMs: Long
    )

    /**
     * Model information for debugging/display.
     */
    data class ModelInfo(
        val inputShape: List<Int>,
        val outputShape: List<Int>,
        val signatures: List<String>,
        val isMultilingual: Boolean
    )
}
