package com.pimax.whisperflow

import com.google.android.gms.wearable.*
import com.pimax.whisperflow.sync.CaptureProtocol

/** Event driven bridge only. File I/O and inference are owned by persistent workers. */
class VoiceMemoryReceiver : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(CaptureProtocol.AUDIO_PREFIX) == true }) {
            // Listener callbacks run on the service's handler thread. Commit the small work
            // request before returning; do not rely on a process staying alive afterwards.
            WatchInboxWorker.enqueue(applicationContext).result.get()
        }
    }
    override fun onPeerConnected(peer: Node) { WatchInboxWorker.enqueue(applicationContext).result.get() }
}
