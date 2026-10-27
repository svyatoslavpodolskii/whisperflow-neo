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
}
