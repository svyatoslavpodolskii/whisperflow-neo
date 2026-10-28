package com.pimax.whisperflow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.pimax.whisperflow.local.ModelManager
import com.pimax.whisperflow.local.TranscriptionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Inference never runs in the Data Layer listener. All watch recordings use this serial chain. */
class WatchTranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val repository = AudioInboxRepository.get(applicationContext)
        val recording = repository.find(id) ?: return Result.success()
        if (recording.deleted || recording.state == "DONE") return Result.success()
        val engine = TranscriptionEngine(applicationContext)
        try {
            if (SettingsDataStore(applicationContext).showTranscriptionStatus.first()) setForeground(foreground(id))
            repository.update(id, "PROCESSING")
            val settings = SettingsDataStore(applicationContext)
            val server = settings.serverUrl.first()
            val selected = settings.inferenceMode.first()
            val mode = if (selected == "LOCAL" || server.isBlank()) TranscriptionEngine.TranscriptionMode.LOCAL
                else TranscriptionEngine.TranscriptionMode.valueOf(selected)
            engine.setMode(mode)
            val language = settings.language.first()
            engine.configureServer(server, language, deriveServiceUrl(server, 8068))
            engine.configureTask(settings.task.first())
            if (mode == TranscriptionEngine.TranscriptionMode.LOCAL) {
                val model = ModelManager.WhisperModel.valueOf(settings.localModel.first())
                if (!engine.modelManager.isModelInstalled(model) || !engine.modelManager.isVocabInstalled()) {
                    repository.update(id, "FAILED", error = applicationContext.getString(R.string.memory_model_required))
                    return Result.success()
                }
                engine.initializeLocal(model).getOrThrow()
            }
            val bytes = File(recording.audioPath).readBytes()
            // Existing local engine has a 30-second input. Do not silently truncate long captures.
            val text = if (mode == TranscriptionEngine.TranscriptionMode.LOCAL) {
                val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = FloatArray(pcm.remaining()) { pcm.get() / 32768f }
                buildList {
                    var offset = 0
                    while (offset < samples.size) {
                        val end = minOf(offset + 30 * 16000, samples.size)
                        add(engine.transcribe(samples.copyOfRange(offset, end), language).text.trim())
                        offset = end
                    }
                }.filter { it.isNotEmpty() }.joinToString(" ")
            } else engine.transcribeWav(bytes, language).text.trim()
            if (text.isBlank()) {
                repository.update(id, "FAILED", error = applicationContext.getString(R.string.memory_empty_result))
                return Result.success()
            }
            repository.update(id, "DONE", text)
            return Result.success()
        } catch (cancelled: CancellationException) {
            repository.update(id, "PENDING", recording.transcription)
            throw cancelled // WorkManager resumes interrupted persistent work; audio remains intact.
        } catch (error: Exception) {
            repository.update(id, "FAILED", error = error.message ?: applicationContext.getString(R.string.memory_transcription_failed))
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        } finally {
            engine.close()
            runCatching { NoteVaultSyncWorker.enqueue(applicationContext) }
        }
    }

    private fun foreground(id: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("transcription", applicationContext.getString(R.string.memory_channel), NotificationManager.IMPORTANCE_LOW))
        val openNote = Intent(applicationContext, MainActivity::class.java)
            .putExtra(NoteNotifications.NOTE_ID_EXTRA, id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val intent = PendingIntent.getActivity(applicationContext, id.hashCode(), openNote, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "transcription")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(applicationContext.getString(R.string.memory_processing))
            .setContentIntent(intent).setOngoing(true).setSilent(true).build()
        return ForegroundInfo((id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        fun enqueue(context: Context, id: String): Operation {
            val request = OneTimeWorkRequestBuilder<WatchTranscriptionWorker>()
                .setInputData(workDataOf("id" to id))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            return WorkManager.getInstance(context).enqueueUniqueWork("voice-memory-transcription", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
