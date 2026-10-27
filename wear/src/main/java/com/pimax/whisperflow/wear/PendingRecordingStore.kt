package com.pimax.whisperflow.wear

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Local outbox; a transcription result must never authorize audio deletion. */
class PendingRecordingStore(context: Context) {
    private val directory = File(context.filesDir, "pending_recordings")

    fun save(wav: ByteArray): String {
        require(wav.size > 44 && wav.size % 2 == 0)
        require(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")
        check(directory.isDirectory || directory.mkdirs())
        val id = UUID.randomUUID().toString()
        val audio = File(directory, "$id.wav")
        // An orphan WAV remains recoverable if writing metadata fails.
        atomicWrite(audio, wav)
        val metadata = JSONObject().apply {
            put("id", id)
            put("createdAt", System.currentTimeMillis())
            put("durationMs", (wav.size - 44).toLong() * 1000 / 32000)
            put("localPath", audio.absolutePath)
            put("fileSize", wav.size)
            put("syncState", "LOCAL")
            put("retryCount", 0)
            put("lastAttemptAt", JSONObject.NULL)
        }
        atomicWrite(File(directory, "$id.json"), metadata.toString().toByteArray(Charsets.UTF_8))
        return id
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
