package com.pimax.whisperflow.wear

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import com.pimax.whisperflow.sync.CaptureProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Local outbox; a transcription result must never authorize audio deletion. */
class PendingRecordingStore(context: Context) {
    private val directory = File(context.filesDir, "pending_recordings")
    private val spoolDirectory = File(context.filesDir, "capture_spool")
    companion object {
        private val lock = Any()
        private val revision = MutableStateFlow(0L)
    }
    data class Entry(val id: String, val file: File, val createdAt: Long, val durationMs: Long, val sha256: String)
    val entries = revision.map { pending() }.flowOn(Dispatchers.IO)

    fun save(wav: ByteArray, id: String = UUID.randomUUID().toString()): String = synchronized(lock) {
        val durationMs = CaptureProtocol.validateWav(wav)
        require(CaptureProtocol.validId(id))
        check(directory.isDirectory || directory.mkdirs())
        val audio = File(directory, "$id.wav")
        // An orphan WAV remains recoverable if writing metadata fails.
        atomicWrite(audio, wav)
        val metadata = JSONObject().apply {
            put("id", id)
            put("createdAt", System.currentTimeMillis())
            put("durationMs", durationMs)
            put("localPath", audio.absolutePath)
            put("fileSize", wav.size)
            put("sha256", CaptureProtocol.sha256(wav))
            put("syncState", "LOCAL")
            put("retryCount", 0)
            put("lastAttemptAt", JSONObject.NULL)
        }
        atomicWrite(File(directory, "$id.json"), metadata.toString().toByteArray(Charsets.UTF_8))
        revision.update { it + 1 }
        id
    }

    /** Recover a WAV whose metadata write was interrupted; no polling or in-memory queue. */
    fun pending(): List<Entry> = synchronized(lock) {
        spoolDirectory.listFiles { f -> f.extension == "pcm" }?.forEach { raw ->
            val id = raw.nameWithoutExtension
            if (CaptureProtocol.validId(id) && !WearAudioRecorder.isSpoolActive(id)) {
                try {
                    if (raw.length() == 0L) raw.delete()
                    else {
                        val metadata = File(directory, "$id.json")
                        if (!metadata.exists()) {
                            val bytes = raw.readBytes()
                            // A torn final sample is unusable; preserve all complete PCM16 samples.
                            save(WearWav.encode(bytes.copyOf(bytes.size - bytes.size % 2)), id)
                        }
                        // Keep the journal until both WAV and metadata are durable.
                        raw.delete()
                    }
                } catch (error: Exception) { android.util.Log.e("CaptureOutbox", "Cannot recover $id; raw audio retained", error) }
            }
        }
        directory.listFiles { f -> f.extension == "wav" }?.mapNotNull { audio ->
            try {
                val id = audio.nameWithoutExtension
                if (!CaptureProtocol.validId(id)) return@mapNotNull null
                val meta = File(directory, "$id.json")
                if (!meta.exists() || runCatching { JSONObject(meta.readText()) }.isFailure) {
                    val bytes = audio.readBytes()
                    val duration = CaptureProtocol.validateWav(bytes)
                    atomicWrite(meta, JSONObject().apply {
                        put("id", id); put("createdAt", audio.lastModified()); put("durationMs", duration)
                        put("localPath", audio.absolutePath); put("fileSize", audio.length())
                        put("sha256", CaptureProtocol.sha256(bytes)); put("syncState", "LOCAL")
                        put("retryCount", 0); put("lastAttemptAt", JSONObject.NULL)
                    }.toString().toByteArray(Charsets.UTF_8))
                }
                val data = JSONObject(meta.readText())
                if (data.optString("syncState") == "DELIVERED") {
                    if (audio.delete()) meta.delete()
                    return@mapNotNull null
                }
                // Earlier Phase 1 metadata did not have a checksum.
                if (!data.has("sha256")) {
                    data.put("sha256", CaptureProtocol.sha256(audio.readBytes()))
                    atomicWrite(meta, data.toString().toByteArray(Charsets.UTF_8))
                }
                Entry(id, audio, data.getLong("createdAt"), data.getLong("durationMs"), data.getString("sha256"))
            } catch (error: Exception) {
                android.util.Log.e("CaptureOutbox", "Cannot queue ${audio.name}; audio retained", error)
                null
            }
        }?.sortedBy { it.createdAt } ?: emptyList()
    }

    fun attempted(id: String, success: Boolean) = synchronized(lock) {
        require(CaptureProtocol.validId(id))
        val file = File(directory, "$id.json")
        if (file.exists()) {
            val data = JSONObject(file.readText())
            if (data.optString("syncState") != "DELIVERED") {
                data.put("syncState", if (success) "QUEUED" else "FAILED")
                data.put("lastAttemptAt", System.currentTimeMillis())
                if (!success) data.put("retryCount", data.optInt("retryCount") + 1)
                atomicWrite(file, data.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** A checksum-bound durable phone ACK is the only deletion authority. */
    fun acknowledge(id: String, sha256: String): Boolean = synchronized(lock) {
        if (!CaptureProtocol.validId(id)) return@synchronized false
        val meta = File(directory, "$id.json")
        if (!meta.exists()) return@synchronized !File(directory, "$id.wav").exists()
        val data = JSONObject(meta.readText())
        if (data.optString("sha256") != sha256) return@synchronized false
        data.put("syncState", "DELIVERED")
        atomicWrite(meta, data.toString().toByteArray(Charsets.UTF_8))
        val audio = File(directory, "$id.wav")
        if (audio.exists() && !audio.delete()) return@synchronized false
        meta.delete()
        revision.update { it + 1 }
        true
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
