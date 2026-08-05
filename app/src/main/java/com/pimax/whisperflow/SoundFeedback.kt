package com.pimax.whisperflow

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "🔊 SoundFeedback"
private const val SAMPLE_RATE = 44100

object SoundFeedback {

    private var startTrack: AudioTrack? = null
    private var stopTrack: AudioTrack? = null
    private var successTrack: AudioTrack? = null
    private var errorTrack: AudioTrack? = null
    private var taskSavedTrack: AudioTrack? = null
    private var isInitialized = false
    private var vibrator: Vibrator? = null

    // Settings configuration
    var soundEnabled: Boolean = true
    var vibrationEnabled: Boolean = true
    var vibrationIntensity: Float = 0.8f

    fun initialize(context: Context? = null) {
        if (isInitialized) return
        try {
            startTrack = buildTrack(generateStartBlip())
            stopTrack = buildTrack(generateStopBlip())
            successTrack = buildTrack(generateSuccessChime())
            errorTrack = buildTrack(generateErrorTone())
            taskSavedTrack = buildTrack(generateTaskSavedChime())

            if (context != null) {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    mgr?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            }

            isInitialized = true
            Log.d(TAG, "Sound effects initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize sounds: ${e.message}")
        }
    }

    fun playStart() {
        Log.d(TAG, "playStart: soundEnabled=$soundEnabled, vibrationEnabled=$vibrationEnabled, intensity=$vibrationIntensity, hasVibrator=${vibrator != null}")
        if (soundEnabled) {
            playTrack(startTrack)
        }
        if (vibrationEnabled) {
            vibrateStrongOneShot(150)
        }
    }

    fun playStop() {
        Log.d(TAG, "playStop: soundEnabled=$soundEnabled, vibrationEnabled=$vibrationEnabled, intensity=$vibrationIntensity, hasVibrator=${vibrator != null}")
        if (soundEnabled) {
            playTrack(stopTrack)
        }
        if (vibrationEnabled) {
            vibrateStrongPattern(longArrayOf(0, 80, 60, 80), intArrayOf(0, 255, 0, 255))
        }
    }

    fun playSuccess() {
        Log.d(TAG, "playSuccess: soundEnabled=$soundEnabled, vibrationEnabled=$vibrationEnabled, intensity=$vibrationIntensity, hasVibrator=${vibrator != null}")
        if (soundEnabled) {
            playTrack(successTrack)
        }
        if (vibrationEnabled) {
            vibrateStrongPattern(longArrayOf(0, 80, 60, 80, 60, 150), intArrayOf(0, 255, 0, 255, 0, 255))
        }
    }

    fun playError() {
        Log.d(TAG, "playError: soundEnabled=$soundEnabled, vibrationEnabled=$vibrationEnabled, intensity=$vibrationIntensity, hasVibrator=${vibrator != null}")
        if (soundEnabled) {
            playTrack(errorTrack)
        }
        if (vibrationEnabled) {
            vibrateStrongOneShot(400)
        }
    }

    fun playTaskSaved() {
        Log.d(TAG, "playTaskSaved: soundEnabled=$soundEnabled, vibrationEnabled=$vibrationEnabled, intensity=$vibrationIntensity, hasVibrator=${vibrator != null}")
        if (soundEnabled) {
            playTrack(taskSavedTrack)
        }
        if (vibrationEnabled) {
            vibrateStrongPattern(longArrayOf(0, 60, 40, 60, 40, 100), intArrayOf(0, 255, 0, 255, 0, 255))
        }
    }

    fun release() {
        startTrack?.release()
        stopTrack?.release()
        successTrack?.release()
        errorTrack?.release()
        taskSavedTrack?.release()
        startTrack = null
        stopTrack = null
        successTrack = null
        errorTrack = null
        taskSavedTrack = null
        vibrator = null
        isInitialized = false
    }

    private fun playTrack(track: AudioTrack?) {
        try {
            track?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.reloadStaticData()
                it.play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playback failed: ${e.message}")
        }
    }

    private fun vibrateStrongOneShot(durationMs: Long) {
        try {
            val v = vibrator
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val maxAmplitude = (vibrationIntensity * 255).toInt().coerceIn(1, 255)
                    Log.d(TAG, "vibrateStrongOneShot: duration=$durationMs, amplitude=$maxAmplitude")
                    // Use a waveform representation of a one-shot to bypass Samsung's prebaked haptic interception
                    val effect = VibrationEffect.createWaveform(
                        longArrayOf(0, durationMs),
                        intArrayOf(0, maxAmplitude),
                        -1
                    )
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                    @Suppress("DEPRECATION")
                    v.vibrate(effect, audioAttributes)
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(durationMs)
                }
            } else {
                Log.w(TAG, "vibrateStrongOneShot: vibrator is null!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrateStrongOneShot failed: ${e.message}", e)
        }
    }

    private fun vibrateStrongPattern(pattern: LongArray, amplitudes: IntArray) {
        try {
            val v = vibrator
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val scaledAmplitudes = IntArray(amplitudes.size) { i ->
                        if (amplitudes[i] > 0) {
                            (amplitudes[i] * vibrationIntensity).toInt().coerceIn(1, 255)
                        } else {
                            0
                        }
                    }
                    Log.d(TAG, "vibrateStrongPattern: timings=${pattern.joinToString()}, amplitudes=${scaledAmplitudes.joinToString()}")
                    val effect = VibrationEffect.createWaveform(pattern, scaledAmplitudes, -1)
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                    @Suppress("DEPRECATION")
                    v.vibrate(effect, audioAttributes)
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, -1)
                }
            } else {
                Log.w(TAG, "vibrateStrongPattern: vibrator is null!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrateStrongPattern failed: ${e.message}", e)
        }
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack {
        val bufferSize = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        return track
    }

    // ─── Tone Generators ─────────────────────────────────────

    /**
     * Record START: warm soft tick. Barely there — just enough to confirm.
     * Single 380Hz tone, 35ms, very quiet.
     */
    private fun generateStartBlip(): ShortArray {
        return generateTone(380.0, 35, 0.18f, fadeIn = true, fadeOut = true)
    }

    /**
     * Record STOP: warm descending chirp. A gentle "done" sound.
     * 340Hz → 260Hz, 55ms total, quiet.
     */
    private fun generateStopBlip(): ShortArray {
        val tone1 = generateTone(340.0, 25, 0.15f, fadeIn = true, fadeOut = false)
        val tone2 = generateTone(260.0, 30, 0.12f, fadeIn = false, fadeOut = true)
        return tone1 + tone2
    }

    /**
     * Transcription SUCCESS: warm two-note ascending chime.
     * C5 → G5, 100ms total. Warm and satisfying but unobtrusive.
     */
    private fun generateSuccessChime(): ShortArray {
        val n1 = generateTone(520.0, 40, 0.16f, fadeIn = true, fadeOut = true)
        val gap = ShortArray((SAMPLE_RATE * 0.008).toInt())
        val n2 = generateTone(780.0, 50, 0.13f, fadeIn = true, fadeOut = true)
        return n1 + gap + n2
    }

    /**
     * Transcription ERROR: deep soft tone. Noticeable but not jarring.
     * 200Hz, 75ms.
     */
    private fun generateErrorTone(): ShortArray {
        return generateTone(200.0, 75, 0.18f, fadeIn = true, fadeOut = true)
    }

    /**
     * Task SAVED: bright triple-note ascending chime.
     * E5 → G5 → B5, 120ms total. Distinctive "task added" confirmation.
     */
    private fun generateTaskSavedChime(): ShortArray {
        val n1 = generateTone(660.0, 30, 0.14f, fadeIn = true, fadeOut = true)  // E5
        val gap1 = ShortArray((SAMPLE_RATE * 0.006).toInt())
        val n2 = generateTone(784.0, 30, 0.13f, fadeIn = true, fadeOut = true)  // G5
        val gap2 = ShortArray((SAMPLE_RATE * 0.006).toInt())
        val n3 = generateTone(988.0, 45, 0.12f, fadeIn = true, fadeOut = true)  // B5
        return n1 + gap1 + n2 + gap2 + n3
    }

    /**
     * Generate a sine wave tone with soft harmonics and cosine-interpolated envelope.
     */
    private fun generateTone(
        frequencyHz: Double,
        durationMs: Int,
        volume: Float,
        fadeIn: Boolean,
        fadeOut: Boolean
    ): ShortArray {
        val numSamples = (SAMPLE_RATE * durationMs / 1000.0).toInt()
        val samples = ShortArray(numSamples)
        val fadeSamples = (numSamples * 0.45).toInt().coerceAtLeast(1)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE

            // Pure sine with very gentle 2nd harmonic — clean and soft
            var sample = sin(2.0 * PI * frequencyHz * t)
            sample += 0.08 * sin(4.0 * PI * frequencyHz * t) // subtle warmth
            sample *= 0.9 // normalize

            // Cosine-interpolated envelope for click-free edges
            var envelope = 1.0
            if (fadeIn && i < fadeSamples) {
                val progress = i.toDouble() / fadeSamples
                envelope *= (1.0 - cos(progress * PI)) / 2.0
            }
            if (fadeOut && i > numSamples - fadeSamples) {
                val progress = (numSamples - i).toDouble() / fadeSamples
                envelope *= (1.0 - cos(progress * PI)) / 2.0
            }

            val value = (sample * envelope * volume * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            samples[i] = value.toShort()
        }

        return samples
    }
}
