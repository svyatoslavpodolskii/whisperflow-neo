package com.pimax.whisperflow

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.pimax.whisperflow.sync.CaptureProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** An explicitly selected SAF folder is an ordinary Markdown vault, not an application database export. */
class NoteVaultSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val notes = MarkdownNotes.get(applicationContext)
        try {
            notes.pruneTrash()
            for (row in AudioInboxRepository.get(applicationContext).list()) {
                notes.materialize(row.id, row.createdAt, row.transcription, "WATCH", row.audioPath)
            }
            val legacy = JSONArray(SettingsDataStore(applicationContext).history.first())
            for (index in 0 until legacy.length()) {
                val row = legacy.getJSONObject(index)
                val timestamp = row.getLong("timestamp")
                val id = UUID.nameUUIDFromBytes("phone-history:$timestamp".toByteArray()).toString()
                notes.materialize(id, timestamp, row.optString("text"), "PHONE", row.optString("audio_file").takeIf { it.isNotBlank() })
            }
            val vault = notes.vaultUri ?: return@withContext Result.success()
            val root = checkNotNull(DocumentFile.fromTreeUri(applicationContext, Uri.parse(vault)))
            check(root.isDirectory && root.canWrite())
            val documents = mutableMapOf<String, DocumentFile>()
            fun scan(directory: DocumentFile, prefix: String = "", depth: Int = 0) {
                if (depth > 12) return
                for (doc in directory.listFiles()) {
                    val name = doc.name ?: continue
                    if (name.startsWith(".") || name == "attachments") continue
                    if (doc.isDirectory) scan(doc, "$prefix$name/", depth + 1)
                    else if (name.endsWith(".md", ignoreCase = true)) documents[prefix + name] = doc
                }
            }
            scan(root)
            for ((path, document) in documents) {
                check(notes.vaultUri == vault)
                try { notes.importDocument(read(document), path, document.uri.toString(), documents.values.map { it.uri.toString() }.toSet()) }
                catch (error: Exception) { Log.w("NoteVault", "Cannot import $path; external file unchanged", error) }
            }
            // Second pass includes conflict copies created during the first pass.
            repeat(2) {
                for (note in notes.list(includeDeleted = true)) {
                    check(notes.vaultUri == vault)
                    val remote = note.documentUri?.let { uri -> documents.values.firstOrNull { it.uri.toString() == uri } }
                        ?: documents[note.fileName]
                    if (note.deleted) {
                        if (remote != null && remote.delete()) documents.entries.removeAll { it.value.uri == remote.uri }
                        if (note.deleteAudio && note.recordingId != null) root.findFile("attachments")?.findFile(note.recordingId + ".wav")?.delete()
                        continue
                    }
                    val localText = notes.read(note.id)
                    val localHash = CaptureProtocol.sha256(localText.toByteArray(Charsets.UTF_8))
                    val remoteText = remote?.let { read(it) }
                    val remoteHash = remoteText?.let { CaptureProtocol.sha256(it.toByteArray(Charsets.UTF_8)) }
                    when (decideNoteSync(localHash, remoteHash, note.syncedHash)) {
                        NoteSyncDecision.PULL -> {
                            notes.pull(note.id, checkNotNull(remoteText), localHash)
                            notes.synced(note.id, checkNotNull(remote).uri.toString(), checkNotNull(remoteHash))
                        }
                        NoteSyncDecision.CONFLICT -> {
                            notes.conflict(note.id, checkNotNull(remoteText)) // Preserve external edit before any overwrite.
                            val doc = remote ?: create(root, note.fileName)
                            write(doc, localText.toByteArray(Charsets.UTF_8))
                            notes.synced(note.id, doc.uri.toString(), localHash)
                            documents[note.fileName] = doc
                        }
                        NoteSyncDecision.PUSH -> {
                            val doc = remote ?: create(root, note.fileName)
                            write(doc, localText.toByteArray(Charsets.UTF_8))
                            notes.synced(note.id, doc.uri.toString(), localHash)
                            documents[note.fileName] = doc
                        }
                        NoteSyncDecision.SAME -> notes.synced(note.id, checkNotNull(remote).uri.toString(), localHash)
                    }
                    val audio = note.audioPath?.let(::File)?.takeIf { it.exists() }
                    val recording = note.recordingId
                    if (audio != null && recording != null) {
                        val attachments = root.findFile("attachments") ?: checkNotNull(root.createDirectory("attachments"))
                        val name = "$recording.wav"
                        val existing = attachments.findFile(name)
                        if (existing == null || existing.length() != audio.length()) {
                            val target = existing ?: checkNotNull(attachments.createFile("audio/wav", name))
                            applicationContext.contentResolver.openOutputStream(target.uri, "wt").use { output ->
                                checkNotNull(output)
                                audio.inputStream().use { it.copyTo(output, 65536) }
                            }
                            check(target.length() == audio.length())
                        }
                    }
                }
            }
            notes.syncMessage = applicationContext.getString(R.string.notes_sync_done)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            Log.e("NoteVault", "Vault sync failed; private Markdown retained", error)
            notes.syncMessage = applicationContext.getString(R.string.notes_sync_failed)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private fun read(document: DocumentFile): String = applicationContext.contentResolver.openInputStream(document.uri).use {
        val bytes = checkNotNull(it).readBytesBounded(2 * 1024 * 1024)
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    private fun create(root: DocumentFile, name: String): DocumentFile {
        // New files use a single safe filename; imported nested files retain their document URI.
        val safe = name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_")
        return checkNotNull(root.createFile("text/markdown", safe))
    }
    private fun write(document: DocumentFile, bytes: ByteArray) {
        applicationContext.contentResolver.openOutputStream(document.uri, "wt").use { checkNotNull(it).write(bytes) }
        check(CaptureProtocol.sha256(read(document).toByteArray(Charsets.UTF_8)) == CaptureProtocol.sha256(bytes))
    }
    companion object {
        fun enqueue(context: Context): Operation = WorkManager.getInstance(context).enqueueUniqueWork(
            "markdown-vault", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<NoteVaultSyncWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        )
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer); if (count < 0) break
        check(output.size() + count <= limit) { "Markdown file is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
