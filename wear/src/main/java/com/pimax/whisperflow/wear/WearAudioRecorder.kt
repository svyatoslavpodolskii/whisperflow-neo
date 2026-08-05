package com.pimax.whisperflow.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

private const val TAG = "🎙️ WearRecorder"

private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val MAX_DURATION_MS = 15_000L
private const val SILENCE_THRESHOLD = 800      // amplitude below this = silence
private const val SPEECH_THRESHOLD = 1500      // amplitude above this = definitely speech
private const val SILENCE_TIMEOUT_MS = 1800L   // auto-stop after this much silence post-speech

class WearAudioRecorder(private val context: Context) {

    var isRecording: Boolean = false
        private set

    /** Normalized amplitude 0.0–1.0, updated ~30fps during recording */
    var currentAmplitude: Float = 0f
        private set

    private var audioRecord: AudioRecord? = null
    private var pcmBuffer: ByteArrayOutputStream? = null
    private var recordingJob: Job? = null

    private var speechDetected = false
    private var silenceStartMs = 0L

    /**
     * Start recording. Returns immediately.
     * [onAmplitudeUpdate] called ~30fps with normalized amplitude (0.0–1.0).
     * [onAutoStop] called when silence detection triggers auto-stop.
     * [onMaxDuration] called when 15s limit is reached.
     */
    suspend fun startRecording(
        onAmplitudeUpdate: (Float) -> Unit = {},
        onAutoStop: () -> Unit = {},
        onMaxDuration: () -> Unit = {}
    ) {
        if (isRecording) {
            Log.w(TAG, "Already recording, ignoring start")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        val bufferSize = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            SAMPLE_RATE * 2 // at least 1 second buffer
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord: ${e.message}")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }

        pcmBuffer = ByteArrayOutputStream()
        speechDetected = false
        silenceStartMs = 0L
        isRecording = true
        currentAmplitude = 0f

        val startTime = System.currentTimeMillis()

        coroutineScope {
            recordingJob = launch(Dispatchers.IO) {
                val buffer = ShortArray(bufferSize / 2)
                audioRecord?.startRecording()
                Log.d(TAG, "Recording started (bufferSize=$bufferSize)")

                while (isActive && isRecording) {
                    val elapsed = System.currentTimeMillis() - startTime

                    // Max duration check
                    if (elapsed >= MAX_DURATION_MS) {
                        Log.d(TAG, "Max duration reached (${MAX_DURATION_MS}ms)")
                        isRecording = false
                        withContext(Dispatchers.Main) { onMaxDuration() }
                        break
                    }

                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read <= 0) {
                        delay(10)
                        continue
                    }

                    // Write PCM data to buffer
                    val byteArray = ByteArray(read * 2)
                    for (i in 0 until read) {
                        byteArray[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        byteArray[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    pcmBuffer?.write(byteArray)

                    // Compute amplitude
                    var maxAmp = 0
                    for (i in 0 until read) {
                        maxAmp = max(maxAmp, abs(buffer[i].toInt()))
                    }
                    val normalizedAmp = (maxAmp / 32768f).coerceIn(0f, 1f)
                    currentAmplitude = normalizedAmp

                    withContext(Dispatchers.Main) {
                        onAmplitudeUpdate(normalizedAmp)
                    }

                    // Silence detection (only after speech has been detected)
                    if (maxAmp > SPEECH_THRESHOLD) {
                        speechDetected = true
                        silenceStartMs = 0L
                    } else if (speechDetected && maxAmp < SILENCE_THRESHOLD) {
                        if (silenceStartMs == 0L) {
                            silenceStartMs = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - silenceStartMs >= SILENCE_TIMEOUT_MS) {
                            Log.d(TAG, "Silence detected for ${SILENCE_TIMEOUT_MS}ms, auto-stopping")
                            isRecording = false
                            withContext(Dispatchers.Main) { onAutoStop() }
                            break
                        }
                    } else if (speechDetected && maxAmp >= SILENCE_THRESHOLD && maxAmp <= SPEECH_THRESHOLD) {
                        // Ambiguous zone — don't reset silence timer
                    }
                }

                audioRecord?.stop()
                Log.d(TAG, "Recording stopped, PCM bytes: ${pcmBuffer?.size() ?: 0}")
            }
        }
    }

    /**
     * Get intermediate WAV data without stopping the recording.
     * Used for live transcription chunks.
     */
    fun getIntermediateWav(): ByteArray? {
        if (!isRecording) return null

        val pcmData = pcmBuffer?.toByteArray()
        if (pcmData == null || pcmData.size < 3200) { // At least 0.1s of audio
            return null
        }

        // Return WAV of current buffer without clearing it
        return encodeWav(pcmData)
    }

    /**
     * Stop recording and return the WAV data as a byte array (in-memory, no file I/O).
     * Returns null if no audio was captured.
     */
    fun stopAndGetWav(): ByteArray? {
        isRecording = false
        currentAmplitude = 0f

        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop() failed: ${e.message}")
        }
        audioRecord?.release()
        audioRecord = null

        val pcmData = pcmBuffer?.toByteArray()
        pcmBuffer = null

        if (pcmData == null || pcmData.isEmpty()) {
            Log.w(TAG, "No PCM data captured")
            return null
        }

        // Skip very short recordings (< 0.3s)
        val durationMs = (pcmData.size.toLong() * 1000L) / (SAMPLE_RATE * 2)
        if (durationMs < 300) {
            Log.w(TAG, "Recording too short: ${durationMs}ms, discarding")
            return null
        }

        Log.d(TAG, "Encoding WAV: ${pcmData.size} PCM bytes, ~${durationMs}ms")
        return encodeWav(pcmData)
    }

    /** Force-stop without returning data */
    fun cancel() {
        isRecording = false
        currentAmplitude = 0f
        recordingJob?.cancel()
        recordingJob = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        pcmBuffer = null
    }

    /**
     * Encode raw PCM data into a WAV byte array (16kHz, mono, 16-bit).
     * WAV header is 44 bytes.
     */
    private fun encodeWav(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val wav = ByteArrayOutputStream(44 + pcmData.size)

        // RIFF header
        wav.write("RIFF".toByteArray())
        wav.write(intToLittleEndian(totalDataLen))
        wav.write("WAVE".toByteArray())

        // fmt sub-chunk
        wav.write("fmt ".toByteArray())
        wav.write(intToLittleEndian(16))          // sub-chunk size
        wav.write(shortToLittleEndian(1))          // PCM format
        wav.write(shortToLittleEndian(channels))
        wav.write(intToLittleEndian(SAMPLE_RATE))
        wav.write(intToLittleEndian(byteRate))
        wav.write(shortToLittleEndian(blockAlign))
        wav.write(shortToLittleEndian(bitsPerSample))

        // data sub-chunk
        wav.write("data".toByteArray())
        wav.write(intToLittleEndian(pcmData.size))
        wav.write(pcmData)

        return wav.toByteArray()
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte()
        )
    }
}
