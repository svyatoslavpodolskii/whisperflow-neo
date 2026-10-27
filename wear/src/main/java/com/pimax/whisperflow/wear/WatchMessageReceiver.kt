package com.pimax.whisperflow.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Node
import com.pimax.whisperflow.sync.CaptureProtocol

private const val TAG = "📨 WatchMsgReceiver"

/**
 * Background WearableListenerService on the watch side.
 *
 * Receives messages from the phone even when WearMainActivity is not in the foreground.
 * This ensures transcription results, errors, and status updates are not lost
 * if the user turns their wrist or the watch screen times out during transcription.
 *
 * Messages are forwarded to the activity via a local broadcast intent.
 * If the activity is alive, it picks up the broadcast. If not, the message
 * is logged and a notification could be shown (future enhancement).
 */
class WatchMessageReceiver : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(CaptureProtocol.ACK_PREFIX) == true }) {
            CaptureSyncWorker.enqueue(applicationContext).result.get()
        }
    }

    override fun onPeerConnected(peer: Node) { CaptureSyncWorker.enqueue(applicationContext).result.get() }

    companion object {
        const val ACTION_WHISPER_MESSAGE = "com.pimax.whisperflow.wear.WHISPER_MESSAGE"
        const val EXTRA_PATH = "path"
        const val EXTRA_DATA = "data"
        const val EXTRA_SOURCE_NODE = "source_node"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data
        val sourceNode = messageEvent.sourceNodeId

        Log.d(TAG, "Message received: path=$path, size=${data.size}, from=$sourceNode")

        when (path) {
            PATH_RESULT -> {
                val text = String(data, Charsets.UTF_8)
                Log.d(TAG, "Transcription result (background): ${text.take(100)}")
                broadcastToActivity(path, data, sourceNode)
            }

            PATH_ERROR -> {
                val error = String(data, Charsets.UTF_8)
                Log.w(TAG, "Transcription error (background): $error")
                broadcastToActivity(path, data, sourceNode)
            }

            PATH_STATUS -> {
                val status = String(data, Charsets.UTF_8)
                Log.d(TAG, "Phone status update (background): $status")
                broadcastToActivity(path, data, sourceNode)
            }

            PATH_PONG -> {
                Log.d(TAG, "Pong received (background)")
                broadcastToActivity(path, data, sourceNode)
            }

            else -> {
                Log.d(TAG, "Unknown message path: $path")
            }
        }
    }

    /**
     * Forward the message to the activity via a local broadcast.
     * The activity registers a BroadcastReceiver for ACTION_WHISPER_MESSAGE
     * and handles the message in the UI context.
     */
    private fun broadcastToActivity(path: String, data: ByteArray, sourceNode: String) {
        val intent = Intent(ACTION_WHISPER_MESSAGE).apply {
            setPackage(packageName)
            putExtra(EXTRA_PATH, path)
            putExtra(EXTRA_DATA, data)
            putExtra(EXTRA_SOURCE_NODE, sourceNode)
        }
        sendBroadcast(intent)
    }
}
