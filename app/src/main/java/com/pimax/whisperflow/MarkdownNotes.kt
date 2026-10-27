package com.pimax.whisperflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pimax.whisperflow.sync.CaptureProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class MarkdownNote(val id: String, val title: String, val fileName: String, val createdAt: Long,
    val updatedAt: Long, val recordingId: String?, val audioPath: String?, val edited: Boolean,
    val transcript: String, val documentUri: String?, val syncedHash: String?, val deleted: Boolean)

enum class NoteSyncDecision { SAME, PUSH, PULL, CONFLICT }
fun decideNoteSync(local: String, remote: String?, baseline: String?): NoteSyncDecision = when {
    local == remote -> NoteSyncDecision.SAME
    remote == null || remote == baseline -> NoteSyncDecision.PUSH
    local == baseline -> NoteSyncDecision.PULL
    else -> NoteSyncDecision.CONFLICT
}

/** Markdown files are the content; SQLite is a recoverable index and synchronization journal. */
class MarkdownNotes internal constructor(private val context: Context) : SQLiteOpenHelper(context, "markdown_notes.db", null, 1) {
    private val directory = File(context.filesDir, "notes").apply { check(isDirectory || mkdirs()) }
    private val changes = MutableStateFlow(0L)
    private val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draftVersions = mutableMapOf<String, Long>()
    private val draftCache = mutableMapOf<String, Pair<String, String>>()
    val notes = changes.map { list() }.flowOn(Dispatchers.IO)
    val trash = changes.map { list(true).filter { it.deleted } }.flowOn(Dispatchers.IO)
    val vault = changes.map { vaultUri }
    val status = changes.map { syncMessage }
    private var recovered = false
    private val preferences = context.getSharedPreferences("markdown_vault", Context.MODE_PRIVATE)
    var vaultUri: String?
        get() = preferences.getString("uri", null)
        @Synchronized set(value) {
            preferences.edit().putString("uri", value).commit()
            writableDatabase.execSQL("UPDATE notes SET documentUri=NULL, syncedHash=NULL")
            changes.update { it + 1 }
        }
    var syncMessage: String
        get() = preferences.getString("sync_message", "") ?: ""
        set(value) { preferences.edit().putString("sync_message", value).apply(); changes.update { it + 1 } }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE notes(id TEXT PRIMARY KEY, title TEXT NOT NULL, fileName TEXT NOT NULL,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, recordingId TEXT, audioPath TEXT,
            edited INTEGER NOT NULL DEFAULT 0, transcript TEXT NOT NULL DEFAULT '',
            documentUri TEXT, syncedHash TEXT, deleted INTEGER NOT NULL DEFAULT 0)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun read(id: String): String { require(CaptureProtocol.validId(id)); return File(directory, "$id.md").readText(Charsets.UTF_8) }
    fun hash(id: String): String = CaptureProtocol.sha256(read(id).toByteArray(Charsets.UTF_8))
    fun find(id: String): MarkdownNote? = readableDatabase.query("notes", null, "id=?", arrayOf(id), null, null, null).use { c ->
        if (!c.moveToFirst()) null else {
            fun str(key: String) = c.getString(c.getColumnIndexOrThrow(key))
            fun long(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
            MarkdownNote(str("id"), str("title"), str("fileName"), long("createdAt"), long("updatedAt"),
                str("recordingId"), str("audioPath"), long("edited") != 0L, str("transcript"), str("documentUri"), str("syncedHash"), long("deleted") != 0L)
        }
    }
    fun list(includeDeleted: Boolean = false): List<MarkdownNote> {
        recoverOrphans()
        return readableDatabase.rawQuery(
        "SELECT id FROM notes ${if (includeDeleted) "" else "WHERE deleted=0"} ORDER BY updatedAt DESC", null
        ).use { c -> buildList { while (c.moveToNext()) find(c.getString(0))?.let { add(it) } } }
    }
    @Synchronized private fun recoverOrphans() {
        if (recovered) return
        recovered = true
        directory.listFiles { file -> file.extension == "md" }?.forEach { file ->
            val id = file.nameWithoutExtension
            if (CaptureProtocol.validId(id) && find(id) == null) runCatching {
                val capture = AudioInboxRepository.get(context).find(id)
                create(file.readText(Charsets.UTF_8), id, createdAt = file.lastModified(),
                    recordingId = capture?.id, audioPath = capture?.audioPath, transcript = capture?.transcription ?: "")
            }.onFailure { android.util.Log.e("MarkdownNotes", "Orphan Markdown retained: $id", it) }
        }
    }

    @Synchronized fun create(text: String = "# Новая заметка\n\n", id: String = UUID.randomUUID().toString(),
        fileName: String? = null, createdAt: Long = System.currentTimeMillis(), recordingId: String? = null,
        audioPath: String? = null, transcript: String = "", edited: Boolean = true): MarkdownNote {
        require(CaptureProtocol.validId(id))
        find(id)?.let { return it }
        val document = withNoteId(text, id)
        write(id, document)
        val name = fileName ?: (SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date(createdAt)) + " ${id.take(8)}.md")
        val values = ContentValues().apply {
            put("id", id); put("title", title(document)); put("fileName", name)
            put("createdAt", createdAt); put("updatedAt", createdAt); put("recordingId", recordingId)
            put("audioPath", audioPath); put("transcript", transcript); put("edited", if (edited) 1 else 0)
        }
        writableDatabase.insertOrThrow("notes", null, values)
        changes.update { it + 1 }
        return checkNotNull(find(id))
    }

    /** A late transcript can add text but cannot replace an already edited note. */
    @Synchronized fun materialize(id: String, createdAt: Long, transcript: String, source: String, audioPath: String?) {
        val existing = find(id)
        if (existing?.deleted == true) return
        val heading = transcript.lineSequence().firstOrNull { it.isNotBlank() }?.take(90) ?: "Голосовая заметка"
        val generated = "---\nsource: $source\ncaptured_at: ${java.time.Instant.ofEpochMilli(createdAt)}\n---\n\n# $heading\n\n" +
            transcript + if (audioPath != null) "\n\n[Аудиозапись](attachments/$id.wav)\n" else "\n"
        if (existing == null) {
            create(generated, id, createdAt = createdAt, recordingId = id, audioPath = audioPath, transcript = transcript, edited = false)
        } else if (existing.transcript != transcript && transcript.isNotBlank()) {
            val updated = if (existing.edited) read(id) + "\n\n## Расшифровка\n\n$transcript\n" else generated
            write(id, withNoteId(updated, id))
            writableDatabase.update("notes", ContentValues().apply {
                put("title", title(updated)); put("transcript", transcript); put("updatedAt", System.currentTimeMillis())
            }, "id=?", arrayOf(id))
            changes.update { it + 1 }
        }
    }

    @Synchronized fun saveEdit(id: String, text: String, expectedHash: String): MarkdownNote {
        val note = checkNotNull(find(id))
        // An external edit or a newly arrived transcript may have changed the file while the editor was open.
        if (hash(id) != expectedHash || note.deleted) return conflict(id, text)
        val document = withNoteId(text, id)
        write(id, document)
        writableDatabase.update("notes", ContentValues().apply {
            put("title", title(document)); put("updatedAt", System.currentTimeMillis()); put("edited", 1)
        }, "id=?", arrayOf(id))
        changes.update { it + 1 }
        return checkNotNull(find(id))
    }

    @Synchronized fun conflict(origin: String, text: String): MarkdownNote {
        val digest = CaptureProtocol.sha256(text.toByteArray(Charsets.UTF_8))
        val id = UUID.nameUUIDFromBytes("conflict:$origin:$digest".toByteArray()).toString()
        return create(text, id, fileName = "Конфликт ${origin.take(8)} ${digest.take(8)}.md")
    }

    @Synchronized fun importDocument(text: String, fileName: String, uri: String, availableUris: Set<String> = emptySet()): MarkdownNote {
        val embedded = embeddedId(text)
        val bound = list(true).firstOrNull { it.documentUri == uri }
        val reusable = embedded?.let { find(it) }?.takeIf { it.documentUri == null || it.documentUri == uri || it.documentUri !in availableUris }
        if (bound != null) return bound
        if (reusable != null) {
            writableDatabase.update("notes", ContentValues().apply { put("documentUri", uri); put("fileName", fileName) }, "id=?", arrayOf(reusable.id))
            return checkNotNull(find(reusable.id))
        }
        val id = embedded?.takeIf { find(it) == null } ?: UUID.nameUUIDFromBytes("document:$uri".toByteArray()).toString()
        val note = create(text, id, fileName)
        synced(note.id, uri, CaptureProtocol.sha256(text.toByteArray(Charsets.UTF_8)))
        return note
    }

    @Synchronized fun pull(id: String, text: String, expectedLocalHash: String) {
        if (hash(id) != expectedLocalHash) { conflict(id, text); return }
        val document = withNoteId(text, id)
        write(id, document)
        writableDatabase.update("notes", ContentValues().apply {
            put("title", title(document)); put("edited", 1); put("updatedAt", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
        changes.update { it + 1 }
    }
    fun synced(id: String, uri: String, remoteHash: String) {
        writableDatabase.update("notes", ContentValues().apply { put("documentUri", uri); put("syncedHash", remoteHash) }, "id=?", arrayOf(id))
    }
    fun delete(id: String) {
        writableDatabase.update("notes", ContentValues().apply { put("deleted", 1) }, "id=?", arrayOf(id))
        // Keep the private Markdown as a recoverable trash copy; never delete the original audio here.
        changes.update { it + 1 }
    }
    @Synchronized fun restore(id: String) {
        writableDatabase.update("notes", ContentValues().apply {
            put("deleted", 0); putNull("documentUri"); putNull("syncedHash")
            put("updatedAt", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
        changes.update { it + 1 }
    }
    @Synchronized fun draft(id: String): Pair<String, String>? = draftCache[id] ?: runCatching {
        val data = org.json.JSONObject(File(directory, "$id.draft.json").readText())
        data.getString("text") to data.getString("baseHash")
    }.getOrNull()
    @Synchronized fun queueDraft(id: String, text: String, baseHash: String) {
        val generation = (draftVersions[id] ?: 0) + 1
        draftVersions[id] = generation
        draftCache[id] = text to baseHash
        draftScope.launch {
            synchronized(this@MarkdownNotes) {
                if (draftVersions[id] == generation) runCatching { saveDraft(id, text, baseHash) }
                    .onFailure { android.util.Log.e("MarkdownNotes", "Draft write failed", it) }
            }
        }
    }
    @Synchronized fun saveDraft(id: String, text: String, baseHash: String) {
        require(CaptureProtocol.validId(id))
        atomicWrite(File(directory, "$id.draft.json"), org.json.JSONObject().put("text", text).put("baseHash", baseHash).toString())
    }
    @Synchronized fun clearDraft(id: String) {
        require(CaptureProtocol.validId(id))
        draftVersions[id] = (draftVersions[id] ?: 0) + 1
        draftCache.remove(id)
        File(directory, "$id.draft.json").delete()
    }
    override fun close() { draftScope.cancel(); super.close() }

    private fun write(id: String, text: String) {
        atomicWrite(File(directory, "$id.md"), text)
    }
    private fun atomicWrite(file: File, text: String) {
        val temporary = File(file.parentFile, file.name + ".new")
        FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        // API 26+, same-filesystem atomic replacement; report a failed rename rather than silently losing an edit.
        java.nio.file.Files.move(temporary.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    companion object {
        @Volatile private var instance: MarkdownNotes? = null
        fun get(context: Context): MarkdownNotes = instance ?: synchronized(this) {
            instance ?: MarkdownNotes(context.applicationContext).also { instance = it }
        }
        fun embeddedId(text: String): String? {
            val normalized = text.replace("\r\n", "\n")
            val end = normalized.indexOf("\n---", 4)
            if (!normalized.startsWith("---\n") || end < 0) return null
            return Regex("(?m)^neo_id: *[\"']?([0-9a-f-]{36})[\"']? *$")
                .find(normalized.substring(4, end))?.groupValues?.get(1)?.takeIf(CaptureProtocol::validId)
        }
        fun withNoteId(text: String, id: String): String {
            val normalized = text.replace("\r\n", "\n")
            if (embeddedId(normalized) == id) return normalized
            if (normalized.startsWith("---\n") && normalized.indexOf("\n---", 4) >= 0) {
                val end = normalized.indexOf("\n---", 4)
                val fields = normalized.substring(4, end).lineSequence().filterNot { it.startsWith("neo_id:") }.joinToString("\n")
                return "---\nneo_id: $id\n$fields\n" + normalized.substring(end + 1)
            }
            return "---\nneo_id: $id\n---\n\n$normalized"
        }
        fun title(text: String): String {
            val body = if (text.startsWith("---\n")) text.substringAfter("\n---\n", text) else text
            return body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.trimStart('#', ' ')?.take(100) ?: "Без названия"
        }
    }
}
