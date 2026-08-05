package com.pimax.whisperflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

private const val TAG = "📡 WatchRelay"

// Must match the paths defined in the wear module's WatchDataSender.kt
private const val PATH_AUDIO = "/whisper/audio"
private const val PATH_RESULT = "/whisper/result"
private const val PATH_ERROR = "/whisper/error"
private const val PATH_STATUS = "/whisper/status"
private const val PATH_PING = "/whisper/ping"
private const val PATH_PONG = "/whisper/pong"

// DataMap keys (must match watch side)
private const val KEY_WAV_ASSET = "wav_asset"
private const val KEY_TIMESTAMP = "timestamp"
private const val KEY_LANGUAGE = "language"

/**
 * Phone-side WearableListenerService that receives audio from the Galaxy Watch,
 * transcribes it via the existing GPU server (WhisperApi), and sends the result
 * text back to the watch via MessageClient.
 *
 * This service runs in the background and is started automatically by Google Play
 * Services when data or messages arrive from the watch on matching paths.
 *
 * Flow:
 * 1. Watch sends a DataItem with WAV Asset on /whisper/audio
 * 2. This service receives it in onDataChanged()
 * 3. Extracts the WAV bytes from the Asset
 * 4. Saves to a temp file (WhisperApi expects a File)
 * 5. Reads server URL + language from SettingsDataStore
 * 6. Calls WhisperApi.transcribe()
 * 7. Sends result text back to watch via MessageClient on /whisper/result
 * 8. Also copies to phone clipboard and saves to history
 * 9. On failure, sends error on /whisper/error
 */
class WatchRelayService : WearableListenerService(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settingsDataStore: SettingsDataStore? = null
    private var dataClient: DataClient? = null
    private var messageClient: MessageClient? = null

    override fun onCreate() {
        super.onCreate()
        settingsDataStore = SettingsDataStore(applicationContext)
        Log.d(TAG, "WatchRelayService created")

        // Manually register as listeners (workaround for GMS not binding to WearableListenerService)
        dataClient = Wearable.getDataClient(applicationContext)
        messageClient = Wearable.getMessageClient(applicationContext)

        // Register WITHOUT filter to receive ALL data events for debugging
        dataClient?.addListener(this)
        messageClient?.addListener(this)
        Log.d(TAG, "Manually registered DataClient listener (NO FILTER - all events)")
        Log.d(TAG, "Manually registered MessageClient listener")

        // Also try to get existing data items
        serviceScope.launch {
            try {
                val dataItems = Wearable.getDataClient(applicationContext).dataItems.await()
                Log.d(TAG, "Existing data items count: ${dataItems.count}")
                for (item in dataItems) {
                    Log.d(TAG, "Existing item: ${item.uri}")
                }
                dataItems.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get existing data items: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove listeners
        dataClient?.removeListener(this)
        messageClient?.removeListener(this as MessageClient.OnMessageReceivedListener)
        Log.d(TAG, "Removed DataClient and MessageClient listeners")

        serviceScope.cancel()
        Log.d(TAG, "WatchRelayService destroyed")
    }

    // ── DataClient: receive WAV audio from watch ────────────────────────────────

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "══════════════════════════════════════════════════")
        Log.d(TAG, "onDataChanged CALLED! Events count: ${dataEvents.count}")

        if (dataEvents.count == 0) {
            Log.w(TAG, "Empty dataEvents buffer!")
            return
        }

        for (event in dataEvents) {
            val eventType = when (event.type) {
                DataEvent.TYPE_CHANGED -> "CHANGED"
                DataEvent.TYPE_DELETED -> "DELETED"
                else -> "UNKNOWN(${event.type})"
            }
            Log.d(TAG, "Event type: $eventType")

            val dataItem = event.dataItem
            val uri = dataItem.uri
            val path = uri.path
            val host = uri.host

            Log.d(TAG, "DataItem URI: $uri")
            Log.d(TAG, "DataItem path: $path")
            Log.d(TAG, "DataItem host: $host")

            if (event.type != DataEvent.TYPE_CHANGED) {
                Log.d(TAG, "Skipping non-CHANGED event")
                continue
            }

            if (path == null) {
                Log.w(TAG, "Path is null, skipping")
                continue
            }

            Log.d(TAG, "Checking if path '$path' matches '$PATH_AUDIO'")

            if (path == PATH_AUDIO || path.startsWith("/whisper")) {
                Log.d(TAG, "✅ PATH MATCHES! Processing audio from watch")
                Log.d(TAG, "Received audio DataItem from watch: ${dataItem.uri}")

                try {
                    val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
                    Log.d(TAG, "DataMap keys: ${dataMap.keySet()}")
                val asset = dataMap.getAsset(KEY_WAV_ASSET)
                val language = dataMap.getString(KEY_LANGUAGE, "Auto") ?: "Auto"
                val timestamp = dataMap.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
                val sourceNodeId = dataItem.uri.host ?: ""

                if (asset == null) {
                    Log.e(TAG, "No WAV asset in DataItem")
                    serviceScope.launch {
                        sendErrorToWatch(sourceNodeId, "No audio data received")
                    }
                    return
                }

                serviceScope.launch {
                    handleWatchAudio(asset, language, timestamp, sourceNodeId)
                }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing DataItem: ${e.message}", e)
                }
            } else {
                Log.d(TAG, "Path '$path' does not match, skipping")
            }
        }
        Log.d(TAG, "══════════════════════════════════════════════════")
    }

    // ── MessageClient: handle ping from watch ───────────────────────────────────

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val sourceNodeId = messageEvent.sourceNodeId
        val dataSize = messageEvent.data?.size ?: 0

        Log.d(TAG, "══════════════════════════════════════════════════")
        Log.d(TAG, "onMessageReceived CALLED!")
        Log.d(TAG, "Message path: $path")
        Log.d(TAG, "Source node: $sourceNodeId")
        Log.d(TAG, "Data size: $dataSize bytes")

        when (path) {
            PATH_PING -> {
                Log.d(TAG, "Ping from watch, sending pong")
                serviceScope.launch {
                    sendPongToWatch(sourceNodeId)

                    // Also check GPU status and send it
                    val settings = settingsDataStore ?: return@launch
                    val serverUrl = settings.serverUrl.first()
                    val api = WhisperApi(serverUrl)
                    val healthy = try { api.checkHealth() } catch (_: Exception) { false }
                    val status = if (healthy) "ready" else "offline"
                    sendStatusToWatch(sourceNodeId, status)
                }
            }
        }
    }

    // ── Core transcription logic ────────────────────────────────────────────────

    private suspend fun handleWatchAudio(
        asset: Asset,
        language: String,
        timestamp: Long,
        sourceNodeId: String
    ) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Processing watch audio (language=$language, timestamp=$timestamp)")

        // Send "busy" status so watch knows we're working
        sendStatusToWatch(sourceNodeId, "busy")

        var tempFile: File? = null

        try {
            // 1. Extract WAV bytes from Asset
            val wavBytes = extractAssetBytes(asset)
            if (wavBytes == null || wavBytes.isEmpty()) {
                Log.e(TAG, "Failed to extract WAV from asset")
                sendErrorToWatch(sourceNodeId, "Failed to read audio")
                return
            }

            Log.d(TAG, "Extracted WAV: ${wavBytes.size} bytes")

            // 2. Write to temp file (WhisperApi.transcribe() expects a File)
            tempFile = File(cacheDir, "watch_audio_${timestamp}.wav")
            FileOutputStream(tempFile).use { fos ->
                fos.write(wavBytes)
            }
            Log.d(TAG, "Temp file: ${tempFile.absolutePath} (${tempFile.length()} bytes)")

            // 3. Read settings
            val settings = settingsDataStore
            if (settings == null) {
                sendErrorToWatch(sourceNodeId, "Settings not available")
                return
            }

            val serverUrl = settings.serverUrl.first()
            val task = settings.task.first()

            // 4. Check GPU health first
            val api = WhisperApi(serverUrl)
            val healthy = try { api.checkHealth() } catch (_: Exception) { false }
            if (!healthy) {
                Log.w(TAG, "GPU server is offline")
                sendErrorToWatch(sourceNodeId, "GPU server offline")
                sendStatusToWatch(sourceNodeId, "offline")
                return
            }

            // 5. Transcribe
            Log.d(TAG, "Calling WhisperApi.transcribe() → $serverUrl")
            val result = api.transcribe(
                audioFile = tempFile,
                language = language,
                task = task
            )

            val elapsed = System.currentTimeMillis() - startTime

            if (result.success && result.text.isNotBlank()) {
                Log.d(TAG, "✅ Watch transcription in ${elapsed}ms: ${result.text.take(100)}")

                // 6. Send result to watch
                sendResultToWatch(sourceNodeId, result.text)

                // 7. Copy to phone clipboard
                copyToClipboard(result.text)

                // 8. Save recording permanently and add to history
                val permanentPath = settings.saveRecordingPermanently(tempFile)
                settings.addToHistory(
                    text = result.text,
                    durationMs = result.durationMs,
                    language = result.language,
                    audioFilePath = permanentPath
                )
                settings.updateLastTranscription(result.text)

                // 9. Send ready status
                sendStatusToWatch(sourceNodeId, "ready")
            } else {
                val error = result.error ?: "Empty transcription"
                Log.w(TAG, "❌ Watch transcription failed: $error")
                sendErrorToWatch(sourceNodeId, error)
                sendStatusToWatch(sourceNodeId, "ready")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Watch transcription error: ${e.message}", e)
            sendErrorToWatch(sourceNodeId, e.message ?: "Transcription failed")
            sendStatusToWatch(sourceNodeId, "ready")
        } finally {
            // Clean up temp file (permanent copy was made if successful)
            tempFile?.let {
                if (it.exists()) {
                    it.delete()
                    Log.d(TAG, "Cleaned up temp file")
                }
            }
        }
    }

    // ── Asset extraction ────────────────────────────────────────────────────────

    private suspend fun extractAssetBytes(asset: Asset): ByteArray? {
        return try {
            val dataClient = Wearable.getDataClient(applicationContext)
            val response = dataClient.getFdForAsset(asset).await()
            val inputStream: InputStream = response.inputStream

            inputStream.use { stream ->
                stream.readBytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract asset: ${e.message}", e)
            null
        }
    }

    // ── Send messages to watch ──────────────────────────────────────────────────

    private suspend fun sendResultToWatch(nodeId: String, text: String) {
        try {
            val messageClient = Wearable.getMessageClient(applicationContext)
            messageClient.sendMessage(
                nodeId,
                PATH_RESULT,
                text.toByteArray(Charsets.UTF_8)
            ).await()
            Log.d(TAG, "Result sent to watch: ${text.take(80)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send result to watch: ${e.message}", e)
        }
    }

    private suspend fun sendErrorToWatch(nodeId: String, error: String) {
        try {
            val messageClient = Wearable.getMessageClient(applicationContext)
            messageClient.sendMessage(
                nodeId,
                PATH_ERROR,
                error.toByteArray(Charsets.UTF_8)
            ).await()
            Log.w(TAG, "Error sent to watch: $error")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send error to watch: ${e.message}", e)
        }
    }

    private suspend fun sendStatusToWatch(nodeId: String, status: String) {
        try {
            val messageClient = Wearable.getMessageClient(applicationContext)
            messageClient.sendMessage(
                nodeId,
                PATH_STATUS,
                status.toByteArray(Charsets.UTF_8)
            ).await()
            Log.d(TAG, "Status sent to watch: $status")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send status to watch: ${e.message}", e)
        }
    }

    private suspend fun sendPongToWatch(nodeId: String) {
        try {
            val messageClient = Wearable.getMessageClient(applicationContext)
            messageClient.sendMessage(
                nodeId,
                PATH_PONG,
                ByteArray(0)
            ).await()
            Log.d(TAG, "Pong sent to watch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send pong to watch: ${e.message}", e)
        }
    }

    // ── Clipboard ───────────────────────────────────────────────────────────────

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("WhisperFlow (Watch)", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "Copied watch transcription to phone clipboard")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy to clipboard: ${e.message}")
        }
    }
}
