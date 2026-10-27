package com.pimax.whisperflow.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Versioned protocol shared by the two APKs; paths never overwrite another capture. */
object CaptureProtocol {
    const val AUDIO_PREFIX = "/voice-memory/v1/audio/"
    const val ACK_PREFIX = "/voice-memory/v1/ack/"
    const val MAX_WAV_BYTES = 19_200_044 // ten minutes, PCM16 mono 16 kHz
    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Accept only the canonical WAV produced by our recorder. Do not ACK truncated audio. */
    fun validateWav(bytes: ByteArray): Long {
        require(bytes.size in 46..MAX_WAV_BYTES && bytes.size % 2 == 0) { "Invalid WAV size" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(bytes, 8, 8, Charsets.US_ASCII) == "WAVEfmt ")
        require(String(bytes, 36, 4, Charsets.US_ASCII) == "data")
        require(b.getInt(4) == bytes.size - 8 && b.getInt(16) == 16 && b.getInt(40) == bytes.size - 44)
        require(b.getShort(20).toInt() == 1 && b.getShort(22).toInt() == 1)
        require(b.getInt(24) == 16000 && b.getInt(28) == 32000)
        require(b.getShort(32).toInt() == 2 && b.getShort(34).toInt() == 16)
        return (bytes.size - 44).toLong() * 1000 / 32000
    }
}
