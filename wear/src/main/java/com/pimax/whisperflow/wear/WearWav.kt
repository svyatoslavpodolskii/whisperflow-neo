package com.pimax.whisperflow.wear

import java.io.ByteArrayOutputStream

internal object WearWav {
    private const val SAMPLE_RATE = 16000

    fun encode(pcmData: ByteArray): ByteArray {
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
