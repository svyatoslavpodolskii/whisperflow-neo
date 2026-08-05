package com.pimax.whisperflow.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Computes mel spectrograms from audio samples for Whisper model input.
 *
 * Whisper expects:
 * - 16kHz mono audio
 * - 80 mel filterbanks
 * - 400-sample FFT window with 160-sample hop
 * - Log-scaled, normalized mel spectrogram
 *
 * Output shape: (80, 3000) for 30 seconds of audio
 */
object MelSpectrogram {

    const val SAMPLE_RATE = 16000
    const val N_FFT = 400
    const val N_MEL = 80
    const val HOP_LENGTH = 160
    const val CHUNK_SIZE_SECONDS = 30
    const val CHUNK_SIZE_SAMPLES = SAMPLE_RATE * CHUNK_SIZE_SECONDS // 480000

    // Expected mel spectrogram length for 30s chunk
    const val MEL_LEN = CHUNK_SIZE_SAMPLES / HOP_LENGTH // 3000

    /**
     * Compute mel spectrogram from audio samples.
     *
     * @param samples PCM float samples (normalized to [-1, 1])
     * @param filters Mel filterbank weights loaded from vocab file (80 x 201)
     * @param numThreads Number of threads for parallel computation
     * @return Mel spectrogram as FloatArray of size (N_MEL * MEL_LEN)
     */
    suspend fun compute(
        samples: FloatArray,
        filters: MelFilters,
        numThreads: Int = Runtime.getRuntime().availableProcessors()
    ): FloatArray = withContext(Dispatchers.Default) {
        // Pad or truncate to exactly CHUNK_SIZE_SAMPLES
        val paddedSamples = when {
            samples.size >= CHUNK_SIZE_SAMPLES -> samples.copyOfRange(0, CHUNK_SIZE_SAMPLES)
            else -> FloatArray(CHUNK_SIZE_SAMPLES).also {
                samples.copyInto(it)
            }
        }

        val meaningfulSamples = minOf(samples.size, CHUNK_SIZE_SAMPLES)
        val meaningfulFrames = meaningfulSamples / HOP_LENGTH

        // Pre-compute Hanning window
        val hann = FloatArray(N_FFT) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / N_FFT))).toFloat()
        }

        val nFft = 1 + N_FFT / 2 // 201
        val melData = FloatArray(N_MEL * MEL_LEN)

        // Parallel computation across frames
        val frameChunks = (0 until numThreads).map { threadIdx ->
            async {
                val fftIn = FloatArray(N_FFT)
                val fftOut = FloatArray(N_FFT * 2)

                // Process frames assigned to this thread
                var frame = threadIdx
                while (frame < meaningfulFrames) {
                    val offset = frame * HOP_LENGTH

                    // Apply Hanning window
                    for (j in 0 until N_FFT) {
                        fftIn[j] = if (offset + j < meaningfulSamples) {
                            hann[j] * paddedSamples[offset + j]
                        } else {
                            0f
                        }
                    }

                    // FFT
                    fft(fftIn, fftOut)

                    // Magnitude squared
                    for (j in 0 until N_FFT) {
                        fftOut[j] = fftOut[2 * j] * fftOut[2 * j] + fftOut[2 * j + 1] * fftOut[2 * j + 1]
                    }

                    // Fold negative frequencies
                    for (j in 1 until N_FFT / 2) {
                        fftOut[j] += fftOut[N_FFT - j]
                    }

                    // Apply mel filterbank
                    for (mel in 0 until N_MEL) {
                        var sum = 0.0
                        for (k in 0 until nFft) {
                            sum += fftOut[k] * filters.data[mel * nFft + k]
                        }

                        // Log scale with floor
                        if (sum < 1e-10) sum = 1e-10
                        melData[mel * MEL_LEN + frame] = log10(sum).toFloat()
                    }

                    frame += numThreads
                }

                // Pad remaining frames with silence value
                frame = threadIdx + meaningfulFrames
                while (frame < MEL_LEN) {
                    for (mel in 0 until N_MEL) {
                        melData[mel * MEL_LEN + frame] = -8.0f
                    }
                    frame += numThreads
                }
            }
        }

        frameChunks.awaitAll()

        // Clamping and normalization
        var maxVal = -1e20f
        for (i in 0 until N_MEL * MEL_LEN) {
            if (melData[i] > maxVal) maxVal = melData[i]
        }

        val clampMin = maxVal - 8.0f
        for (i in 0 until N_MEL * MEL_LEN) {
            if (melData[i] < clampMin) {
                melData[i] = clampMin
            }
            melData[i] = (melData[i] + 4.0f) / 4.0f
        }

        melData
    }

    /**
     * Synchronous version for simpler use cases.
     */
    fun computeSync(
        samples: FloatArray,
        filters: MelFilters,
        numThreads: Int = Runtime.getRuntime().availableProcessors()
    ): FloatArray {
        // Pad or truncate to exactly CHUNK_SIZE_SAMPLES
        val paddedSamples = when {
            samples.size >= CHUNK_SIZE_SAMPLES -> samples.copyOfRange(0, CHUNK_SIZE_SAMPLES)
            else -> FloatArray(CHUNK_SIZE_SAMPLES).also {
                samples.copyInto(it)
            }
        }

        val meaningfulSamples = minOf(samples.size, CHUNK_SIZE_SAMPLES)
        val meaningfulFrames = meaningfulSamples / HOP_LENGTH

        // Pre-compute Hanning window
        val hann = FloatArray(N_FFT) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / N_FFT))).toFloat()
        }

        val nFft = 1 + N_FFT / 2 // 201
        val melData = FloatArray(N_MEL * MEL_LEN)

        // Use threads directly for synchronous computation
        val threads = (0 until numThreads).map { threadIdx ->
            Thread {
                val fftIn = FloatArray(N_FFT)
                val fftOut = FloatArray(N_FFT * 2)

                var frame = threadIdx
                while (frame < meaningfulFrames) {
                    val offset = frame * HOP_LENGTH

                    for (j in 0 until N_FFT) {
                        fftIn[j] = if (offset + j < meaningfulSamples) {
                            hann[j] * paddedSamples[offset + j]
                        } else {
                            0f
                        }
                    }

                    fft(fftIn, fftOut)

                    for (j in 0 until N_FFT) {
                        fftOut[j] = fftOut[2 * j] * fftOut[2 * j] + fftOut[2 * j + 1] * fftOut[2 * j + 1]
                    }

                    for (j in 1 until N_FFT / 2) {
                        fftOut[j] += fftOut[N_FFT - j]
                    }

                    for (mel in 0 until N_MEL) {
                        var sum = 0.0
                        for (k in 0 until nFft) {
                            sum += fftOut[k] * filters.data[mel * nFft + k]
                        }
                        if (sum < 1e-10) sum = 1e-10
                        melData[mel * MEL_LEN + frame] = log10(sum).toFloat()
                    }

                    frame += numThreads
                }

                frame = threadIdx + meaningfulFrames
                while (frame < MEL_LEN) {
                    for (mel in 0 until N_MEL) {
                        melData[mel * MEL_LEN + frame] = -8.0f
                    }
                    frame += numThreads
                }
            }.also { it.start() }
        }

        threads.forEach { it.join() }

        // Clamping and normalization
        var maxVal = -1e20f
        for (i in 0 until N_MEL * MEL_LEN) {
            if (melData[i] > maxVal) maxVal = melData[i]
        }

        val clampMin = maxVal - 8.0f
        for (i in 0 until N_MEL * MEL_LEN) {
            if (melData[i] < clampMin) {
                melData[i] = clampMin
            }
            melData[i] = (melData[i] + 4.0f) / 4.0f
        }

        return melData
    }

    /**
     * Radix-2 Cooley-Tukey FFT implementation.
     *
     * @param input Real input samples of size N
     * @param output Complex output of size N*2 (interleaved real/imag)
     */
    private fun fft(input: FloatArray, output: FloatArray) {
        val n = input.size

        if (n == 1) {
            output[0] = input[0]
            output[1] = 0f
            return
        }

        // Fall back to DFT for non-power-of-2
        if (n % 2 != 0) {
            dft(input, output)
            return
        }

        // Split into even and odd
        val even = FloatArray(n / 2)
        val odd = FloatArray(n / 2)

        for (i in 0 until n) {
            if (i % 2 == 0) {
                even[i / 2] = input[i]
            } else {
                odd[i / 2] = input[i]
            }
        }

        val evenFft = FloatArray(n)
        val oddFft = FloatArray(n)

        fft(even, evenFft)
        fft(odd, oddFft)

        // Combine
        for (k in 0 until n / 2) {
            val theta = 2.0 * PI * k / n
            val cosTheta = cos(theta).toFloat()
            val sinTheta = (-sin(theta)).toFloat()

            val oddReal = oddFft[2 * k]
            val oddImag = oddFft[2 * k + 1]

            val tReal = cosTheta * oddReal - sinTheta * oddImag
            val tImag = cosTheta * oddImag + sinTheta * oddReal

            output[2 * k] = evenFft[2 * k] + tReal
            output[2 * k + 1] = evenFft[2 * k + 1] + tImag

            output[2 * (k + n / 2)] = evenFft[2 * k] - tReal
            output[2 * (k + n / 2) + 1] = evenFft[2 * k + 1] - tImag
        }
    }

    /**
     * Direct DFT for non-power-of-2 sizes (fallback).
     */
    private fun dft(input: FloatArray, output: FloatArray) {
        val n = input.size
        for (k in 0 until n) {
            var re = 0f
            var im = 0f
            for (j in 0 until n) {
                val angle = 2.0 * PI * k * j / n
                re += (input[j] * cos(angle)).toFloat()
                im -= (input[j] * sin(angle)).toFloat()
            }
            output[2 * k] = re
            output[2 * k + 1] = im
        }
    }
}

/**
 * Mel filterbank weights loaded from Whisper vocab file.
 *
 * Shape: (nMel=80, nFft=201)
 */
data class MelFilters(
    val nMel: Int,
    val nFft: Int,
    val data: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MelFilters) return false
        return nMel == other.nMel && nFft == other.nFft && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = nMel
        result = 31 * result + nFft
        result = 31 * result + data.contentHashCode()
        return result
    }
}
