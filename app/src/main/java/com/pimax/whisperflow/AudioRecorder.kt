package com.pimax.whisperflow

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

private const val TAG = "🎙️ AudioRecorder"

private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val AMPLITUDE_UPDATE_INTERVAL_MS = 50L

class AudioRecorder(private val context: Context) {

    enum class State {
        IDLE,
        RECORDING,
        STOPPED
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var outputFile: File? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private var recordStartTime: Long = 0L

    private val chunkLock = Object()
    private var chunkStream: ByteArrayOutputStream? = null
    private var chunkCounter = 0
    private var chunkPeakAmplitude = 0f

    // Audio focus management
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    val lastRecordingFile: File?
        get() = outputFile?.takeIf { it.exists() && it.length() > 44 }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun start(): Boolean {
        if (_state.value == State.RECORDING) {
            Log.w(TAG, "Already recording")
            return false
        }

        if (!hasPermission()) {
            Log.e(TAG, "No RECORD_AUDIO permission")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        val actualBufferSize = maxOf(bufferSize, SAMPLE_RATE * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                actualBufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord", e)
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        outputFile = File(context.cacheDir, "whisperflow_recording.wav")
        pcmStream = ByteArrayOutputStream()
        chunkStream = ByteArrayOutputStream()
        chunkCounter = 0
        chunkPeakAmplitude = 0f
        isRecording = true
        recordStartTime = System.currentTimeMillis()

        // Request audio focus to pause background media
        requestAudioFocus()

        audioRecord?.startRecording()
        _state.value = State.RECORDING
        _amplitude.value = 0f
        _durationMs.value = 0L

        recordingThread = Thread({
            Log.d(TAG, "Recording thread started")
            val buffer = ShortArray(bufferSize / 2)
            var lastAmplitudeUpdate = 0L

            while (isRecording) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (readCount > 0) {
                    val byteBuffer = ByteArray(readCount * 2)
                    for (i in 0 until readCount) {
                        byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    pcmStream?.write(byteBuffer)
                    synchronized(chunkLock) {
                        chunkStream?.write(byteBuffer)
                        val rms = calculateRms(buffer, readCount)
                        if (rms > chunkPeakAmplitude) {
                            chunkPeakAmplitude = rms
                        }
                    }

                    val now = System.currentTimeMillis()
                    _durationMs.value = now - recordStartTime

                    if (now - lastAmplitudeUpdate >= AMPLITUDE_UPDATE_INTERVAL_MS) {
                        lastAmplitudeUpdate = now
                        val rms = calculateRms(buffer, readCount)
                        _amplitude.value = rms
                    }
                } else if (readCount < 0) {
                    Log.e(TAG, "AudioRecord read error: $readCount")
                    break
                }
            }

            Log.d(TAG, "Recording thread ended")
        }, "WhisperFlow-Recorder")

        recordingThread?.start()
        Log.d(TAG, "Recording started — sampleRate=$SAMPLE_RATE, bufferSize=$actualBufferSize")
        return true
    }

    fun stop(): File? {
        if (_state.value != State.RECORDING) {
            Log.w(TAG, "Not recording")
            return null
        }

        isRecording = false

        try {
            recordingThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted waiting for recording thread")
        }
        recordingThread = null

        audioRecord?.release()
        audioRecord = null

        // Abandon audio focus to resume background media
        abandonAudioFocus()

        _state.value = State.STOPPED
        _amplitude.value = 0f

        val pcmData = pcmStream?.toByteArray()
        pcmStream?.close()
        pcmStream = null
        synchronized(chunkLock) {
            chunkStream?.close()
            chunkStream = null
        }

        if (pcmData == null || pcmData.isEmpty()) {
            Log.e(TAG, "No PCM data captured")
            return null
        }

        val wavFile = outputFile ?: return null
        try {
            writeWavFile(wavFile, pcmData)
            val durationSec = pcmData.size.toFloat() / (SAMPLE_RATE * 2)
            Log.d(TAG, "WAV saved: ${wavFile.absolutePath} (${wavFile.length()} bytes, ${durationSec}s)")
            return wavFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write WAV file", e)
            return null
        }
    }

    data class ChunkResult(val file: File, val peakAmplitude: Float)

    fun saveChunk(): ChunkResult? {
        if (_state.value != State.RECORDING) return null

        val pcmData: ByteArray
        val peak: Float
        synchronized(chunkLock) {
            val stream = chunkStream ?: return null
            pcmData = stream.toByteArray()
            peak = chunkPeakAmplitude
            stream.reset()
            chunkPeakAmplitude = 0f
        }

        if (pcmData.isEmpty()) return null

        val minBytes = SAMPLE_RATE * 2 / 4
        if (pcmData.size < minBytes) return null

        val chunkFile = File(context.cacheDir, "whisperflow_chunk_${chunkCounter++}.wav")
        try {
            writeWavFile(chunkFile, pcmData)
            val durationSec = pcmData.size.toFloat() / (SAMPLE_RATE * 2)
            Log.d(TAG, "Chunk saved: ${chunkFile.name} (${chunkFile.length()} bytes, ${durationSec}s, peak=${"%.3f".format(peak)})")
            return ChunkResult(chunkFile, peak)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write chunk WAV", e)
            return null
        }
    }

    fun reset() {
        if (_state.value == State.RECORDING) {
            stop()
        } else {
            abandonAudioFocus()
        }
        synchronized(chunkLock) {
            chunkStream?.close()
            chunkStream = null
        }
        _state.value = State.IDLE
        _amplitude.value = 0f
        _durationMs.value = 0L
    }

    fun release() {
        isRecording = false
        try {
            recordingThread?.join(1000)
        } catch (_: InterruptedException) {}
        recordingThread = null
        audioRecord?.release()
        audioRecord = null
        abandonAudioFocus()
        pcmStream?.close()
        pcmStream = null
        synchronized(chunkLock) {
            chunkStream?.close()
            chunkStream = null
        }
        _state.value = State.IDLE
        _amplitude.value = 0f
        _durationMs.value = 0L
    }

    private fun calculateRms(buffer: ShortArray, readCount: Int): Float {
        if (readCount <= 0) return 0f
        var sum = 0.0
        for (i in 0 until readCount) {
            val sample = buffer[i].toDouble() / Short.MAX_VALUE
            sum += sample * sample
        }
        val rms = sqrt(sum / readCount).toFloat()
        return (rms * 4f).coerceIn(0f, 1f)
    }

    private fun writeWavFile(file: File, pcmData: ByteArray) {
        val totalDataLen = pcmData.size + 36
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8

        FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToByteArrayLE(totalDataLen))
            fos.write("WAVE".toByteArray())

            // fmt chunk
            fos.write("fmt ".toByteArray())
            fos.write(intToByteArrayLE(16)) // chunk size
            fos.write(shortToByteArrayLE(1)) // PCM format
            fos.write(shortToByteArrayLE(channels.toShort()))
            fos.write(intToByteArrayLE(SAMPLE_RATE))
            fos.write(intToByteArrayLE(byteRate))
            fos.write(shortToByteArrayLE((channels * bitsPerSample / 8).toShort())) // block align
            fos.write(shortToByteArrayLE(bitsPerSample.toShort()))

            // data chunk
            fos.write("data".toByteArray())
            fos.write(intToByteArrayLE(pcmData.size))
            fos.write(pcmData)
        }
    }

    private fun intToByteArrayLE(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToByteArrayLE(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            (value.toInt() shr 8 and 0xFF).toByte()
        )
    }

    companion object {
        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val tenths = (ms % 1000) / 100
            return if (minutes > 0) {
                "%d:%02d.%d".format(minutes, seconds, tenths)
            } else {
                "%d.%d".format(seconds, tenths)
            }
        }
    }

    private fun requestAudioFocus() {
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { /* No-op */ }
                .build()

            focusRequest?.let { audioManager?.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                { /* No-op */ },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        Log.d(TAG, "🔇 Audio focus requested")
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus { /* No-op */ }
        }
        focusRequest = null
        Log.d(TAG, "🔊 Audio focus abandoned")
    }
}
