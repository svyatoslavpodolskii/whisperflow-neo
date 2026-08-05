package com.pimax.whisperflow.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "📡 WatchDataSender"

// Data paths
const val PATH_AUDIO = "/whisper/audio"
const val PATH_RESULT = "/whisper/result"
const val PATH_ERROR = "/whisper/error"
const val PATH_STATUS = "/whisper/status"
const val PATH_PING = "/whisper/ping"
const val PATH_PONG = "/whisper/pong"

// DataMap keys
private const val KEY_WAV_ASSET = "wav_asset"
private const val KEY_TIMESTAMP = "timestamp"
private const val KEY_LANGUAGE = "language"

// Phone app capability (declared in phone app's wear.xml)
private const val PHONE_CAPABILITY = "whisperflow_transcribe"

// Timeouts
private const val TRANSCRIPTION_TIMEOUT_MS = 30_000L
private const val PING_TIMEOUT_MS = 3_000L

/**
 * Handles all Wearable Data Layer communication from the watch side.
 *
 * - Sends WAV audio to the phone via DataClient (Asset for large payloads)
 * - Receives transcription results via MessageClient listener
 * - Provides ping/pong connectivity check to phone
 */
class WatchDataSender(private val context: Context) {

    private val dataClient: DataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    private val capabilityClient: CapabilityClient by lazy { Wearable.getCapabilityClient(context) }

    private var resultContinuation: CancellableContinuation<TranscriptionResponse>? = null
    private var pongContinuation: CancellableContinuation<Boolean>? = null

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        handleMessage(event)
    }

    private var listenerRegistered = false

    /** Register the message listener. Call from onResume or Activity start. */
    fun registerListener() {
        if (!listenerRegistered) {
            messageClient.addListener(messageListener)
            listenerRegistered = true
            Log.d(TAG, "Message listener registered")
        }
    }

    /** Unregister the message listener. Call from onPause or Activity stop. */
    fun unregisterListener() {
        if (listenerRegistered) {
            messageClient.removeListener(messageListener)
            listenerRegistered = false
            Log.d(TAG, "Message listener unregistered")
        }
    }

    /**
     * Send WAV audio to the phone for transcription and wait for the result.
     *
     * @param wavBytes The complete WAV file as a byte array (in-memory from WearAudioRecorder)
     * @param language Language code (e.g. "Auto", "en", "de")
     * @return TranscriptionResponse with text on success, or error message on failure
     */
    suspend fun sendAudioAndAwaitResult(
        wavBytes: ByteArray,
        language: String = "Auto"
    ): TranscriptionResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending ${wavBytes.size} bytes of WAV audio to phone")

            // Find the phone node
            val phoneNodeId = findPhoneNodeId()
            if (phoneNodeId == null) {
                Log.e(TAG, "No phone node found with capability '$PHONE_CAPABILITY'")
                return@withContext TranscriptionResponse.error("Phone not reachable")
            }

            Log.d(TAG, "Phone node: $phoneNodeId")

            // Create a DataItem with the WAV as an Asset
            val asset = Asset.createFromBytes(wavBytes)
            val putDataMapReq = PutDataMapRequest.create(PATH_AUDIO).apply {
                dataMap.putAsset(KEY_WAV_ASSET, asset)
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                dataMap.putString(KEY_LANGUAGE, language)
            }
            // setUrgent() ensures the data is synced immediately rather than batched
            val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()

            // Send the data and wait for acknowledgment
            val dataItem = dataClient.putDataItem(putDataReq).await()
            Log.d(TAG, "DataItem sent: ${dataItem.uri}")

            // Now wait for the transcription result via MessageClient
            val response = withTimeoutOrNull(TRANSCRIPTION_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    resultContinuation = cont
                    cont.invokeOnCancellation {
                        resultContinuation = null
                    }
                }
            }

            if (response != null) {
                Log.d(TAG, "Got transcription response: ${response.text?.take(80) ?: response.error}")
                response
            } else {
                Log.w(TAG, "Transcription timed out after ${TRANSCRIPTION_TIMEOUT_MS}ms")
                TranscriptionResponse.error("Transcription timed out")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio: ${e.message}", e)
            TranscriptionResponse.error(e.message ?: "Send failed")
        }
    }

    /**
     * Ping the phone to check if it's reachable and the WhisperFlow app is responsive.
     * Returns true if the phone responds within PING_TIMEOUT_MS.
     */
    suspend fun pingPhone(): Boolean = withContext(Dispatchers.IO) {
        try {
            val phoneNodeId = findPhoneNodeId()
            if (phoneNodeId == null) {
                Log.d(TAG, "Ping: no phone node found")
                return@withContext false
            }

            messageClient.sendMessage(phoneNodeId, PATH_PING, ByteArray(0)).await()

            val gotPong = withTimeoutOrNull(PING_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    pongContinuation = cont
                    cont.invokeOnCancellation {
                        pongContinuation = null
                    }
                }
            }

            val reachable = gotPong == true
            Log.d(TAG, "Ping result: ${if (reachable) "✅ reachable" else "❌ unreachable"}")
            reachable
        } catch (e: Exception) {
            Log.w(TAG, "Ping failed: ${e.message}")
            false
        }
    }

    /**
     * Check if there is any connected node (quick check, no capability filter).
     */
    suspend fun hasConnectedPhone(): Boolean = withContext(Dispatchers.IO) {
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val hasPhone = nodes.any { it.isNearby }
            Log.d(TAG, "Connected nodes: ${nodes.size}, nearby: ${nodes.count { it.isNearby }}")
            hasPhone
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check connected nodes: ${e.message}")
            false
        }
    }

    /**
     * Find the phone node ID that has the whisperflow_transcribe capability.
     * Falls back to the first nearby connected node if capability is not declared.
     */
    private suspend fun findPhoneNodeId(): String? {
        return try {
            // Try capability-based discovery first
            val capabilityInfo = capabilityClient
                .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
            val bestNode = capabilityInfo.nodes
                .firstOrNull { it.isNearby }
                ?: capabilityInfo.nodes.firstOrNull()

            if (bestNode != null) {
                return bestNode.id
            }

            // Fallback: just get the first connected node (for dev/testing before
            // the phone app declares the capability in wear.xml)
            Log.d(TAG, "No node with capability '$PHONE_CAPABILITY', falling back to connected nodes")
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            nodes.firstOrNull { it.isNearby }?.id ?: nodes.firstOrNull()?.id
        } catch (e: Exception) {
            Log.w(TAG, "Failed to find phone node: ${e.message}")
            // Last resort fallback
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                nodes.firstOrNull()?.id
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback node lookup also failed: ${e2.message}")
                null
            }
        }
    }

    private fun handleMessage(event: MessageEvent) {
        val path = event.path
        val data = event.data

        Log.d(TAG, "Received message: path=$path, size=${data.size}")

        when (path) {
            PATH_RESULT -> {
                val text = String(data, Charsets.UTF_8)
                Log.d(TAG, "Transcription result: ${text.take(100)}")
                resultContinuation?.let { cont ->
                    resultContinuation = null
                    if (cont.isActive) {
                        cont.resume(TranscriptionResponse.success(text))
                    }
                }
            }

            PATH_ERROR -> {
                val error = String(data, Charsets.UTF_8)
                Log.w(TAG, "Transcription error from phone: $error")
                resultContinuation?.let { cont ->
                    resultContinuation = null
                    if (cont.isActive) {
                        cont.resume(TranscriptionResponse.error(error))
                    }
                }
            }

            PATH_STATUS -> {
                val status = String(data, Charsets.UTF_8)
                Log.d(TAG, "Phone status: $status")
                // Status updates are handled by the activity via a separate callback
            }

            PATH_PONG -> {
                Log.d(TAG, "Pong received")
                pongContinuation?.let { cont ->
                    pongContinuation = null
                    if (cont.isActive) {
                        cont.resume(true)
                    }
                }
            }
        }
    }
}

/**
 * Represents a transcription response from the phone.
 */
data class TranscriptionResponse(
    val text: String?,
    val error: String?,
    val success: Boolean
) {
    companion object {
        fun success(text: String) = TranscriptionResponse(
            text = text.trim(),
            error = null,
            success = true
        )

        fun error(message: String) = TranscriptionResponse(
            text = null,
            error = message,
            success = false
        )
    }
}
