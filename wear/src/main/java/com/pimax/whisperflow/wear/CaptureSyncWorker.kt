package com.pimax.whisperflow.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.*
import com.google.android.gms.wearable.*
import com.pimax.whisperflow.sync.CaptureProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** Publish once to the system's durable Data Layer queue, even when the phone is offline. */
class CaptureSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = PendingRecordingStore(applicationContext)
        val client = Wearable.getDataClient(applicationContext)
        return try {
            store.pending() // Recover metadata before validating an ACK for an orphan WAV.
            val node = Wearable.getNodeClient(applicationContext).localNode.await().id
            val ackItems = client.getDataItems(Uri.parse("wear://*${CaptureProtocol.ACK_PREFIX}"), DataClient.FILTER_PREFIX).await()
            try {
                for (item in ackItems) {
                    val data = DataMapItem.fromDataItem(item).dataMap
                    val id = data.getString("id") ?: continue
                    if (item.uri.path != CaptureProtocol.ACK_PREFIX + id || data.getString("watchNode") != node) continue
                    val sha = data.getString("sha256") ?: continue
                    if (store.acknowledge(id, sha)) {
                        client.deleteDataItems(Uri.parse("wear://$node${CaptureProtocol.AUDIO_PREFIX}$id")).await()
                        client.deleteDataItems(item.uri).await()
                    }
                }
            } finally { ackItems.release() }
            var failed = false
            for (entry in store.pending()) {
                if (isStopped) return Result.retry()
                try {
                    val request = PutDataMapRequest.create(CaptureProtocol.AUDIO_PREFIX + entry.id).apply {
                        dataMap.putString("id", entry.id)
                        dataMap.putLong("createdAt", entry.createdAt)
                        dataMap.putLong("durationMs", entry.durationMs)
                        dataMap.putLong("fileSize", entry.file.length())
                        dataMap.putString("sha256", entry.sha256)
                        dataMap.putAsset("wav", Asset.createFromUri(Uri.fromFile(entry.file)))
                    }.asPutDataRequest().setUrgent()
                    client.putDataItem(request).await()
                    store.attempted(entry.id, true)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    Log.w("CaptureSync", "Data Layer enqueue failed for ${entry.id}", error)
                    store.attempted(entry.id, false)
                    failed = true
                }
            }
            if (failed && runAttemptCount < 5) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            Log.w("CaptureSync", "Unable to access Data Layer", error)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            val work = OneTimeWorkRequestBuilder<CaptureSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            // A trigger arriving during a scan must cause a second scan, not get lost under KEEP.
            WorkManager.getInstance(context).enqueueUniqueWork("capture-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}
