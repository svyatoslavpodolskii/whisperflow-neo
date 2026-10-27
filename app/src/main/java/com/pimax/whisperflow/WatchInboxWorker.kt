package com.pimax.whisperflow

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.*
import com.google.android.gms.wearable.*
import com.pimax.whisperflow.sync.CaptureProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** DataEvent buffers never escape the listener; persistent work re-reads durable DataItems. */
class WatchInboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val client = Wearable.getDataClient(applicationContext)
        val repository = AudioInboxRepository.get(applicationContext)
        var failed = false
        try {
            val buffer = client.getDataItems(Uri.parse("wear://*${CaptureProtocol.AUDIO_PREFIX}"), DataClient.FILTER_PREFIX).await()
            try {
                for (item in buffer) {
                    if (isStopped) return Result.retry()
                    try {
                        val data = DataMapItem.fromDataItem(item).dataMap
                        val id = data.getString("id") ?: continue
                        if (!CaptureProtocol.validId(id) || item.uri.path != CaptureProtocol.AUDIO_PREFIX + id) continue
                        val node = item.uri.host ?: continue
                        val sha = data.getString("sha256") ?: continue
                        val size = data.getLong("fileSize")
                        require(size in 46..CaptureProtocol.MAX_WAV_BYTES.toLong())
                        val asset = data.getAsset("wav") ?: continue
                        val descriptor = client.getFdForAsset(asset).await()
                        val bytes = descriptor.inputStream.use { input ->
                            val output = ByteArrayOutputStream(size.toInt())
                            val chunk = ByteArray(16384)
                            while (true) {
                                val count = input.read(chunk)
                                if (count < 0) break
                                check(output.size() + count <= CaptureProtocol.MAX_WAV_BYTES)
                                output.write(chunk, 0, count)
                            }
                            output.toByteArray()
                        }
                        require(bytes.size.toLong() == size)
                        val recording = repository.accept(id, data.getLong("createdAt"), bytes, sha)
                        // Persist the work request before ACK; no window where ACK loses pending work.
                        if (!recording.deleted && recording.state != "DONE") WatchTranscriptionWorker.enqueue(applicationContext, id).result.get()
                        val ack = PutDataMapRequest.create(CaptureProtocol.ACK_PREFIX + id).apply {
                            dataMap.putString("id", id); dataMap.putString("sha256", sha)
                            dataMap.putString("watchNode", node)
                        }.asPutDataRequest().setUrgent()
                        client.putDataItem(ack).await()
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (error: Exception) {
                        Log.e("WatchInbox", "Audio was not acknowledged: ${item.uri}", error)
                        failed = true
                    }
                }
            } finally { buffer.release() }
            return if (failed && runAttemptCount < 5) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            Log.w("WatchInbox", "Data Layer unavailable", error)
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("watch-inbox", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<WatchInboxWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
