package com.pimax.whisperflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import com.pimax.whisperflow.sync.CaptureProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream

data class InboxRecording(
    val id: String, val createdAt: Long, val durationMs: Long, val audioPath: String,
    val sha256: String, val state: String, val transcription: String, val error: String?, val deleted: Boolean
)

/** SQLite inbox commits independently of inference. UUID primary key makes redelivery idempotent. */
class AudioInboxRepository internal constructor(private val context: Context) : SQLiteOpenHelper(context, "voice_memory.db", null, 1) {
    private val changes = MutableStateFlow(0L)
    val recordings = changes.map { list() }.flowOn(Dispatchers.IO)
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE recordings (
            id TEXT PRIMARY KEY, createdAt INTEGER NOT NULL, receivedAt INTEGER NOT NULL,
            durationMs INTEGER NOT NULL, audioPath TEXT NOT NULL, sha256 TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'WATCH', state TEXT NOT NULL DEFAULT 'PENDING',
            transcription TEXT NOT NULL DEFAULT '', error TEXT, deleted INTEGER NOT NULL DEFAULT 0)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun accept(id: String, createdAt: Long, bytes: ByteArray, sha256: String): InboxRecording {
        require(CaptureProtocol.validId(id))
        val duration = CaptureProtocol.validateWav(bytes)
        require(CaptureProtocol.sha256(bytes) == sha256) { "Audio checksum mismatch" }
        val existing = find(id)
        if (existing != null) {
            check(existing.sha256 == sha256) { "UUID reused for different audio" }
            // Keep intentional deletions as tombstones so late redelivery cannot resurrect a card.
            if (existing.deleted) return existing
        }
        val directory = File(context.filesDir, "voice_memory").apply { check(isDirectory || mkdirs()) }
        val audio = File(directory, "$id.wav")
        if (!audio.exists() || CaptureProtocol.sha256(audio.readBytes()) != sha256) {
            val atomic = AtomicFile(audio)
            var stream: FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(bytes)
                stream.fd.sync()
                atomic.finishWrite(stream)
            } catch (error: Exception) { atomic.failWrite(stream); throw error }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", id); put("createdAt", createdAt); put("receivedAt", System.currentTimeMillis())
                put("durationMs", duration); put("audioPath", audio.absolutePath); put("sha256", sha256)
            }
            db.insertWithOnConflict("recordings", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val persisted = checkNotNull(find(id))
        check(persisted.sha256 == sha256)
        changes.update { it + 1 }
        return persisted
    }

    fun find(id: String): InboxRecording? = readableDatabase.query(
        "recordings", null, "id=?", arrayOf(id), null, null, null
    ).use { c ->
        if (!c.moveToFirst()) null else InboxRecording(
            c.getString(c.getColumnIndexOrThrow("id")), c.getLong(c.getColumnIndexOrThrow("createdAt")),
            c.getLong(c.getColumnIndexOrThrow("durationMs")), c.getString(c.getColumnIndexOrThrow("audioPath")),
            c.getString(c.getColumnIndexOrThrow("sha256")), c.getString(c.getColumnIndexOrThrow("state")),
            c.getString(c.getColumnIndexOrThrow("transcription")), c.getString(c.getColumnIndexOrThrow("error")),
            c.getInt(c.getColumnIndexOrThrow("deleted")) != 0
        )
    }

    fun list(): List<InboxRecording> = readableDatabase.rawQuery(
        "SELECT id FROM recordings WHERE deleted=0 ORDER BY createdAt DESC, id", null
    ).use { c -> buildList { while (c.moveToNext()) find(c.getString(0))?.let { add(it) } } }

    fun update(id: String, state: String, transcription: String = "", error: String? = null) {
        writableDatabase.update("recordings", ContentValues().apply {
            put("state", state); put("transcription", transcription); put("error", error)
        }, "id=? AND deleted=0", arrayOf(id))
        changes.update { it + 1 }
    }

    @Synchronized
    fun delete(id: String) {
        val recording = find(id) ?: return
        writableDatabase.update("recordings", ContentValues().apply { put("deleted", 1) }, "id=?", arrayOf(id))
        File(recording.audioPath).delete()
        changes.update { it + 1 }
    }
    @Synchronized fun setTrashed(id: String, trashed: Boolean) {
        val row = find(id) ?: return
        if (row.deleted == trashed) return
        if (!trashed && !File(row.audioPath).exists()) return
        writableDatabase.update("recordings", ContentValues().apply { put("deleted", if (trashed) 1 else 0) }, "id=?", arrayOf(id))
        changes.update { it + 1 }
    }

    companion object {
        @Volatile private var instance: AudioInboxRepository? = null
        fun get(context: Context): AudioInboxRepository = instance ?: synchronized(this) {
            instance ?: AudioInboxRepository(context.applicationContext).also { instance = it }
        }
        internal fun reopenForTests(context: Context): AudioInboxRepository = synchronized(this) {
            instance?.close()
            AudioInboxRepository(context.applicationContext).also { instance = it }
        }
    }
}
