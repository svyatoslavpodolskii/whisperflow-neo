package com.pimax.whisperflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownNotesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() { context.deleteDatabase("markdown_notes.db"); File(context.filesDir, "notes").deleteRecursively() }

    @Test fun markdownSurvivesReopenAndIndexLoss() {
        val repo = MarkdownNotes(context)
        val note = repo.create("# Идея\n\n- пункт\n\n**важно**\n")
        assertTrue(repo.read(note.id).contains("**важно**"))
        assertEquals(note.id, MarkdownNotes.embeddedId(repo.read(note.id)))
        repo.close()
        context.deleteDatabase("markdown_notes.db")
        val reopened = MarkdownNotes(context)
        assertEquals(note.id, reopened.list().single().id)
        assertTrue(reopened.read(note.id).contains("- пункт"))
        reopened.close()
    }

    @Test fun lateTranscriptPreservesUserMarkdownAndEditorConflictsKeepBothVersions() {
        val repo = MarkdownNotes(context); val id = UUID.randomUUID().toString()
        repo.materialize(id, 12345, "", "WATCH", null)
        repo.saveEdit(id, "# Моя мысль\n\nТекст вручную", repo.hash(id))
        assertTrue(repo.read(id), repo.read(id).contains("Текст вручную"))
        assertTrue(repo.find(id).toString(), repo.find(id)!!.edited)
        val oldHash = repo.hash(id)
        repo.materialize(id, 12345, "Распознанная фраза", "WATCH", null)
        assertTrue(repo.read(id).contains("Текст вручную"))
        assertTrue(repo.read(id).contains("Распознанная фраза"))
        val copy = repo.saveEdit(id, "# Незавершённая правка", oldHash)
        assertNotEquals(id, copy.id)
        assertTrue(repo.read(id).contains("Распознанная фраза"))
        assertTrue(repo.read(copy.id).contains("Незавершённая правка"))
        assertEquals(2, repo.list().size)
        repo.close()
    }

    @Test fun renamedVaultNoteKeepsIdentityAndIdLikeBodyTextIsNotMetadata() {
        val repo = MarkdownNotes(context); val note = repo.create("# Название\n\nТекст")
        repo.synced(note.id, "content://vault/old.md", repo.hash(note.id))
        val rebound = repo.importDocument(repo.read(note.id), "Ideas/new.md", "content://vault/new.md", setOf("content://vault/new.md"))
        assertEquals(note.id, rebound.id)
        assertEquals(1, repo.list().size)
        val body = "# Code\nneo_id: ${UUID.randomUUID()}\n"
        assertNull(MarkdownNotes.embeddedId(body))
        assertTrue(MarkdownNotes.withNoteId(body, note.id).endsWith(body))
        repo.close()
    }

    @Test fun synchronizationDecisionsNeverTreatConcurrentChangesAsSafeOverwrite() {
        assertEquals(NoteSyncDecision.SAME, decideNoteSync("same", "same", "old"))
        assertEquals(NoteSyncDecision.PUSH, decideNoteSync("local", "base", "base"))
        assertEquals(NoteSyncDecision.PULL, decideNoteSync("base", "remote", "base"))
        assertEquals(NoteSyncDecision.CONFLICT, decideNoteSync("local", "remote", "base"))
    }
    @Test fun editingBodyHidesMetadataAndPreservesItInTheMarkdownFile() {
        val id = UUID.randomUUID().toString()
        val document = "---\nneo_id: $id\nsource: WATCH\n---\n\n# Мысль\n\n**Важно**\n\n[Аудиозапись](attachments/$id.wav)\n"
        val body = MarkdownNotes.body(document)
        assertEquals("# Мысль\n\n**Важно**", body)
        val saved = MarkdownNotes.replaceBody(document, body + "\n\n- Следующий шаг", id)
        assertTrue(saved.contains("source: WATCH"))
        assertEquals(id, MarkdownNotes.embeddedId(saved))
        assertTrue(saved.contains("attachments/$id.wav"))
        assertTrue(MarkdownNotes.body(saved).contains("- Следующий шаг"))
    }
    @Test fun trashRestoresAudioAndPermanentlyDeletesBothAfterRetention() {
        val repo = MarkdownNotes(context)
        val audio = File(context.filesDir, "delete-test.wav").apply { writeBytes(byteArrayOf(1, 2)) }
        val note = repo.create("# Test", audioPath = audio.absolutePath)
        repo.delete(note.id, withAudio = true)
        assertTrue(audio.exists())
        assertTrue(repo.find(note.id)!!.deleteAudio)
        assertTrue(repo.find(note.id)!!.deleted)
        repo.restore(note.id)
        assertFalse(repo.find(note.id)!!.deleted)
        assertEquals(audio.absolutePath, repo.find(note.id)!!.audioPath)
        assertEquals("# Test", MarkdownNotes.body(repo.read(note.id)))
        repo.delete(note.id)
        repo.pruneTrash(System.currentTimeMillis() + 31L * 86400000)
        assertFalse(audio.exists())
        assertTrue(repo.find(note.id)!!.deleted)
        assertFalse(File(context.filesDir, "notes/" + note.id + ".md").exists())
        repo.close()
    }
    @Test fun watchAudioIsHiddenAndRestoredWithItsNoteAndSevenDayRetentionWorks() {
        val inbox = AudioInboxRepository.get(context)
        inbox.close()
        val bytes = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(4); putShort(1000); putShort(-1000)
        }.array()
        val id = UUID.randomUUID().toString()
        val row = inbox.accept(id, 12345, bytes, com.pimax.whisperflow.sync.CaptureProtocol.sha256(bytes))
        val repo = MarkdownNotes(context)
        repo.materialize(id, 12345, "Текст", "WATCH", row.audioPath)
        repo.delete(id)
        assertTrue(inbox.find(id)!!.deleted)
        assertTrue(File(row.audioPath).exists())
        repo.restore(id)
        assertFalse(inbox.find(id)!!.deleted)
        repo.delete(id)
        repo.retentionDays = 7
        repo.pruneTrash(System.currentTimeMillis() + 8L * 86400000)
        assertTrue(inbox.find(id)!!.deleted)
        assertFalse(File(row.audioPath).exists())
        repo.close()
    }
}
