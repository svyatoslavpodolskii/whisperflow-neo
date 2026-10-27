package com.pimax.whisperflow.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

private const val TAG = "🎙️ WearRecorder"

private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val MAX_DURATION_MS = 10 * 60_000L
private const val SILENCE_THRESHOLD = 800      // amplitude below this = silence
private const val SPEECH_THRESHOLD = 1500      // amplitude above this = definitely speech
private const val SILENCE_TIMEOUT_MS = 1800L   // auto-stop after this much silence post-speech

class WearAudioRecorder(private val context: Context) {
    companion object {
        private val activeSpools = ConcurrentHashMap.newKeySet<String>()
        fun isSpoolActive(id: String) = activeSpools.contains(id)
    }
    @Volatile var isRecording = false
        private set
    @Volatile var recordingId = java.util.UUID.randomUUID().toString()
        private set
    val currentAmplitude: Float get() = pipeline?.amplitude ?: 0f
    private var spool: File? = null
    @Volatile private var pipeline: PcmCapturePipeline? = null
    private var recordingJob: Job? = null
    @Volatile private var recoveringOnClose = false
    val hasPendingAudio: Boolean get() = !isRecording && (spool?.length() ?: 0) > 0

    fun confirmSaved() {
        check(!isRecording)
        spool?.delete()
        activeSpools.remove(recordingId)
    }

    suspend fun startRecording(
        onAmplitudeUpdate: (Float) -> Unit = {},
        onAutoStop: () -> Unit = {},
        onMaxDuration: () -> Unit = {},
        onError: () -> Unit = {},
        autoStopOnSilence: Boolean = false,
        silenceTimeoutMs: Long = 3000
    ) = coroutineScope {
        if (isRecording) return@coroutineScope
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError(); return@coroutineScope
        }
        isRecording = true
        recoveringOnClose = false
        recordingId = java.util.UUID.randomUUID().toString()
        activeSpools.add(recordingId)
        // All microphone setup and disk access are also outside Main.
        recordingJob = launch(Dispatchers.IO) {
            var stream: FileOutputStream? = null
            var monitor: Job? = null
            var failed = false
            try {
                val directory = File(context.filesDir, "capture_spool").apply { check(isDirectory || mkdirs()) }
                val journal = File(directory, "$recordingId.pcm").also { spool = it }
                stream = FileOutputStream(journal)
                val buffered = object : java.io.BufferedOutputStream(stream, 8192) {
                    private var lastFlush = SystemClock.elapsedRealtime()
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        super.write(bytes, offset, length)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFlush >= 200) { flush(); lastFlush = now }
                    }
                }
                val bufferSize = max(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT), SAMPLE_RATE * 4)
                val native = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                if (native.state != AudioRecord.STATE_INITIALIZED) { native.release(); error("AudioRecord initialization failed") }
                val capture = PcmCapturePipeline(object : PcmCapturePipeline.Source {
                    override fun start() = native.startRecording()
                    override fun read(samples: ShortArray): Int = native.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    override fun stop() { runCatching { native.stop() } }
                    override fun release() = native.release()
                }, buffered, prioritizeReader = {
                    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
                        .onFailure { Log.w(TAG, "Audio priority unavailable", it) }
                })
                pipeline = capture
                capture.start()
                if (!isRecording || recoveringOnClose) capture.stop()
                // A slow/blocked Main can skip display updates; it cannot delay the reader.
                monitor = launch(Dispatchers.Main) {
                    var speech = false
                    var silenceSince = 0L
                    while (isRecording) {
                        val amplitude = capture.amplitude
                        onAmplitudeUpdate(amplitude)
                        if (autoStopOnSilence) {
                            val now = SystemClock.elapsedRealtime()
                            if (amplitude > 0.045f) { speech = true; silenceSince = 0 }
                            else if (speech && amplitude < 0.024f) {
                                if (silenceSince == 0L) silenceSince = now
                                if (now - silenceSince >= silenceTimeoutMs) { onAutoStop(); break }
                            }
                        }
                        delay(66)
                    }
                }
                capture.await()
                buffered.flush()
                stream.fd.sync()
                if (capture.reachedLimit && !recoveringOnClose) {
                    isRecording = false
                    withContext(Dispatchers.Main) { onMaxDuration() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                Log.e(TAG, "Audio capture failed; journal retained", error)
            } finally {
                pipeline?.stop()
                monitor?.cancel()
                runCatching { stream?.fd?.sync() }
                runCatching { stream?.close() }
                isRecording = false
                if (recoveringOnClose) {
                    activeSpools.remove(recordingId)
                    CaptureSyncWorker.enqueue(context.applicationContext)
                }
            }
            if (failed && !recoveringOnClose) withContext(Dispatchers.Main) { onError() }
        }
    }

    fun getIntermediateWav(): ByteArray? = null // Watch capture never performs live inference/uploads.

    suspend fun stopAndGetWav(): ByteArray? {
        isRecording = false
        pipeline?.stop()
        recordingJob?.join()
        recordingJob = null
        pipeline = null
        return withContext(Dispatchers.IO) {
            val bytes = spool?.takeIf { it.length() >= 2 }?.readBytes() ?: return@withContext null
            WearWav.encode(bytes.copyOf(bytes.size - bytes.size % 2))
        }
    }

    fun cancel() {
        recoveringOnClose = true
        isRecording = false
        pipeline?.stop()
        // Let the owned job drain and fsync the journal. Do not cancel away pending PCM.
        if (recordingJob?.isCompleted != false) {
            activeSpools.remove(recordingId)
            if (spool?.exists() == true) CaptureSyncWorker.enqueue(context.applicationContext)
        }
    }
}
