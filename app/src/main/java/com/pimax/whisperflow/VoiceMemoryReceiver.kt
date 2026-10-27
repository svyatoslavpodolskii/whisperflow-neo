package com.pimax.whisperflow

import com.google.android.gms.wearable.*
import com.pimax.whisperflow.sync.CaptureProtocol

/** Event driven bridge only. File I/O and inference are owned by persistent workers. */
class VoiceMemoryReceiver : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(CaptureProtocol.AUDIO_PREFIX) == true }) {
            WatchInboxWorker.enqueue(applicationContext)
        }
    }
    override fun onPeerConnected(peer: Node) { WatchInboxWorker.enqueue(applicationContext) }
}
