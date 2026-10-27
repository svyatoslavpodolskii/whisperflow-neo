package com.pimax.whisperflow.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Android owns microphone, encoding and disk writes. No JVM PCM reader during capture. */
class WearAudioRecorder(private val context: Context) {
    companion object {
        private val activeSpools = ConcurrentHashMap.newKeySet<String>()
        fun isSpoolActive(id: String) = activeSpools.contains(id)
    }
    @Volatile var isRecording = false
        private set
    @Volatile var recordingId = UUID.randomUUID().toString()
        private set
    @Volatile var currentAmplitude = 0f
        private set
    private var spool: File? = null
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    @Volatile private var recoveringOnClose = false
    @Volatile private var stopRequested = false
    val hasPendingAudio: Boolean get() = !isRecording && (spool?.length() ?: 0) > 0
    fun confirmSaved() {
        check(!isRecording)
        spool?.delete()
        activeSpools.remove(recordingId)
    }
    suspend fun startRecording(
        onAmplitudeUpdate: (Float) -> Unit = {}, onStarted: () -> Unit = {}, onAutoStop: () -> Unit = {},
        onMaxDuration: () -> Unit = {}, onError: () -> Unit = {},
        autoStopOnSilence: Boolean = false, silenceTimeoutMs: Long = 3000
    ) {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError(); return
        }
        isRecording = true; stopRequested = false; recoveringOnClose = false
        recordingId = UUID.randomUUID().toString()
        val id = recordingId
        activeSpools.add(id)
        recordingJob = owner.launch {
            var native: MediaRecorder? = null
            var started = false
            var failed = false
            var timedOut = false
            var silent = false
            try {
                val directory = File(context.filesDir, "capture_spool").apply { check(isDirectory || mkdirs()) }
                val file = File(directory, "$id.aac").also { spool = it }
                @Suppress("DEPRECATION")
                val recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).also { native = it }
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioChannels(1)
                recorder.setAudioSamplingRate(48000)
                recorder.setAudioEncodingBitRate(96000)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                if (!stopRequested) {
                    recorder.start(); started = true
                    withContext(Dispatchers.Main) { onStarted() }
                    val began = SystemClock.elapsedRealtime()
                    var speech = false
                    var silenceSince = 0L
                    while (!stopRequested) {
                        val now = SystemClock.elapsedRealtime()
                        val amplitude = (recorder.maxAmplitude / 32768f).coerceIn(0f, 1f)
                        currentAmplitude = amplitude
                        withContext(Dispatchers.Main) { onAmplitudeUpdate(amplitude) }
                        if (now - began >= 599_000) { timedOut = true; break }
                        if (autoStopOnSilence) {
                            if (amplitude > .045f) { speech = true; silenceSince = 0 }
                            else if (speech && amplitude < .024f) {
                                if (silenceSince == 0L) silenceSince = now
                                if (now - silenceSince >= silenceTimeoutMs) { silent = true; break }
                            }
                        }
                        delay(80)
                    }
                    recorder.stop(); started = false
                    java.io.FileOutputStream(file, true).use { it.fd.sync() }
                }
            } catch (error: Exception) {
                failed = true
                android.util.Log.e("NeoRecorder", "Native capture failed; source retained", error)
            } finally {
                if (started) runCatching { native?.stop() }
                native?.release()
                currentAmplitude = 0f; isRecording = false
            }
            if (recoveringOnClose) {
                activeSpools.remove(id)
                CaptureSyncWorker.enqueue(context.applicationContext)
                owner.cancel()
            } else withContext(Dispatchers.Main) {
                when { failed -> onError(); timedOut -> onMaxDuration(); silent -> onAutoStop() }
            }
        }
    }
    fun getIntermediateWav(): ByteArray? = null
    suspend fun stopAndGetWav(): ByteArray? {
        stopRequested = true
        recordingJob?.join()
        recordingJob = null
        return withContext(Dispatchers.IO) {
            spool?.takeIf { it.length() > 0 }?.let(NativeCaptureDecoder::wav)
        }
    }
    fun cancel() {
        recoveringOnClose = true; stopRequested = true
        if (recordingJob?.isCompleted != false) {
            activeSpools.remove(recordingId)
            if (spool?.exists() == true) CaptureSyncWorker.enqueue(context.applicationContext)
            owner.cancel()
        }
    }
}
