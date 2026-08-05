package com.pimax.whisperflow.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading, storage, and lifecycle of Whisper TFLite models.
 *
 * Models are downloaded from HuggingFace (DocWolle/whisper_tflite_models) and stored
 * in the app's private files directory.
 *
 * Each model requires two files:
 * - Model file: whisper-{size}.tflite
 * - Vocab file: filters_vocab_multilingual.bin (shared across models)
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"

        // HuggingFace base URL for DocWolle's models
        private const val HF_BASE_URL = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main"

        // Vocab file (shared across all multilingual models)
        private const val VOCAB_FILENAME = "filters_vocab_multilingual.bin"

        // Model directory name
        private const val MODELS_DIR = "whisper_models"

        // Download buffer size (64KB)
        private const val BUFFER_SIZE = 65536
    }

    /**
     * Available Whisper models with metadata.
     */
    enum class WhisperModel(
        val displayName: String,
        val fileName: String,
        val sizeBytes: Long,
        val ramRequired: String,
        val speedEstimate: String,
        val qualityRating: String
    ) {
        TINY(
            displayName = "Tiny",
            fileName = "whisper-tiny-transcribe-translate.tflite",
            sizeBytes = 42_000_000L, // ~42MB
            ramRequired = "~150MB",
            speedEstimate = "3-5s / 30s audio",
            qualityRating = "Good for English"
        ),
        BASE(
            displayName = "Base",
            fileName = "whisper-base-transcribe-translate.tflite",
            sizeBytes = 78_000_000L, // ~78MB
            ramRequired = "~250MB",
            speedEstimate = "6-10s / 30s audio",
            qualityRating = "Good multilingual"
        ),
        SMALL(
            displayName = "Small",
            fileName = "whisper-small-transcribe-translate.tflite",
            sizeBytes = 249_000_000L, // ~249MB
            ramRequired = "~600MB",
            speedEstimate = "15-25s / 30s audio",
            qualityRating = "Great quality"
        );

        val downloadUrl: String
            get() = "$HF_BASE_URL/$fileName"
    }

    // State tracking
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _installedModels = MutableStateFlow<Set<WhisperModel>>(emptySet())
    val installedModels: StateFlow<Set<WhisperModel>> = _installedModels.asStateFlow()

    // Models directory - use external files dir to persist across app reinstalls
    // On Android 10+, this survives app uninstall/reinstall
    // Falls back to internal storage if external not available
    private val modelsDir: File by lazy {
        val externalDir = context.getExternalFilesDir(null)
        val baseDir = if (externalDir != null && externalDir.canWrite()) {
            externalDir
        } else {
            Log.w(TAG, "External storage not available, using internal storage")
            context.filesDir
        }
        File(baseDir, MODELS_DIR).also {
            if (!it.exists()) it.mkdirs()
            Log.d(TAG, "Models directory: ${it.absolutePath}")
        }
    }

    init {
        // Scan for installed models on init
        refreshInstalledModels()
    }

    /**
     * Refresh the list of installed models by scanning the models directory.
     */
    fun refreshInstalledModels() {
        val installed = WhisperModel.values().filter { model ->
            getModelFile(model).exists()
        }.toSet()

        _installedModels.value = installed
        Log.d(TAG, "Installed models: ${installed.map { it.displayName }}")
    }

    /**
     * Check if a specific model is installed.
     */
    fun isModelInstalled(model: WhisperModel): Boolean {
        return getModelFile(model).exists()
    }

    /**
     * Check if the shared vocab file is installed.
     */
    fun isVocabInstalled(): Boolean {
        return getVocabFile().exists()
    }

    /**
     * Get the file path for a model.
     */
    fun getModelFile(model: WhisperModel): File {
        return File(modelsDir, model.fileName)
    }

    /**
     * Get the file path for the shared vocab file.
     */
    fun getVocabFile(): File {
        return File(modelsDir, VOCAB_FILENAME)
    }

    /**
     * Get total storage used by all models.
     */
    fun getTotalStorageUsed(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Download a model from HuggingFace.
     *
     * Downloads both the model file and vocab file (if not already present).
     *
     * @param model The model to download
     * @return Result indicating success or failure
     */
    suspend fun downloadModel(model: WhisperModel): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download of ${model.displayName}")
            _downloadState.value = DownloadState.Downloading(model, 0f, "Preparing...")

            // Download vocab file first if needed
            if (!isVocabInstalled()) {
                _downloadState.value = DownloadState.Downloading(model, 0f, "Downloading vocabulary...")
                downloadFile(
                    url = "$HF_BASE_URL/$VOCAB_FILENAME",
                    destination = getVocabFile(),
                    progressCallback = { progress ->
                        _downloadState.value = DownloadState.Downloading(
                            model = model,
                            progress = progress * 0.1f, // Vocab is ~10% of progress
                            status = "Downloading vocabulary: ${(progress * 100).toInt()}%"
                        )
                    }
                )
            }

            // Download model file
            _downloadState.value = DownloadState.Downloading(model, 0.1f, "Downloading model...")
            downloadFile(
                url = model.downloadUrl,
                destination = getModelFile(model),
                progressCallback = { progress ->
                    _downloadState.value = DownloadState.Downloading(
                        model = model,
                        progress = 0.1f + progress * 0.9f, // Model is remaining 90%
                        status = "Downloading ${model.displayName}: ${(progress * 100).toInt()}%"
                    )
                }
            )

            // Update installed models
            refreshInstalledModels()

            _downloadState.value = DownloadState.Completed(model)
            Log.d(TAG, "Download completed: ${model.displayName}")

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            _downloadState.value = DownloadState.Failed(model, e.message ?: "Unknown error")

            // Clean up partial downloads
            getModelFile(model).delete()

            Result.failure(e)
        }
    }

    /**
     * Download a file from URL to destination with progress tracking.
     */
    private suspend fun downloadFile(
        url: String,
        destination: File,
        progressCallback: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Downloading: $url -> ${destination.absolutePath}")

        val tempFile = File(destination.parent, "${destination.name}.tmp")

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "WhisperFlow-Android/1.0")
            }

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP error: $responseCode ${connection.responseMessage}")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (totalBytes > 0) {
                            progressCallback(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                }
            }

            // Rename temp file to final destination
            if (destination.exists()) {
                destination.delete()
            }
            if (!tempFile.renameTo(destination)) {
                throw Exception("Failed to rename temp file to destination")
            }

            progressCallback(1f)
            Log.d(TAG, "Download complete: ${destination.name} (${downloadedBytes} bytes)")

        } finally {
            // Clean up temp file if it still exists
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Delete a downloaded model.
     *
     * @param model The model to delete
     * @return true if deleted successfully
     */
    fun deleteModel(model: WhisperModel): Boolean {
        val modelFile = getModelFile(model)
        val deleted = if (modelFile.exists()) {
            modelFile.delete()
        } else {
            true
        }

        if (deleted) {
            refreshInstalledModels()
            Log.d(TAG, "Deleted model: ${model.displayName}")
        }

        return deleted
    }

    /**
     * Delete all downloaded models and vocab file.
     *
     * @return true if all files deleted successfully
     */
    fun deleteAllModels(): Boolean {
        var allDeleted = true

        modelsDir.listFiles()?.forEach { file ->
            if (!file.delete()) {
                allDeleted = false
                Log.w(TAG, "Failed to delete: ${file.name}")
            }
        }

        if (allDeleted) {
            refreshInstalledModels()
            Log.d(TAG, "Deleted all models")
        }

        return allDeleted
    }

    /**
     * Cancel an ongoing download.
     */
    fun cancelDownload() {
        val currentState = _downloadState.value
        if (currentState is DownloadState.Downloading) {
            _downloadState.value = DownloadState.Idle

            // Clean up partial download
            getModelFile(currentState.model).delete()
            File(modelsDir, "${currentState.model.fileName}.tmp").delete()

            Log.d(TAG, "Download cancelled: ${currentState.model.displayName}")
        }
    }

    /**
     * Reset download state to idle.
     */
    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Get recommended model based on device capabilities.
     */
    fun getRecommendedModel(): WhisperModel {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024) // MB

        return when {
            maxMemory >= 1024 -> WhisperModel.SMALL
            maxMemory >= 512 -> WhisperModel.BASE
            else -> WhisperModel.TINY
        }
    }

    /**
     * Format bytes to human-readable string.
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    /**
     * Download state sealed class hierarchy.
     */
    sealed class DownloadState {
        object Idle : DownloadState()

        data class Downloading(
            val model: WhisperModel,
            val progress: Float, // 0.0 to 1.0
            val status: String
        ) : DownloadState()

        data class Completed(val model: WhisperModel) : DownloadState()

        data class Failed(
            val model: WhisperModel,
            val error: String
        ) : DownloadState()
    }
}
