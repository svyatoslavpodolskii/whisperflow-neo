package com.pimax.whisperflow

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru-rRU-w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class NotesUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun notesListShowsHumanTitlesAndExplicitStorageLocation() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repo = MarkdownNotes.reopenForTests(context)
        AudioInboxRepository.reopenForTests(context)
        repo.vaultUri = null
        repo.create("# Идеи после прогулки\n\nЗаписать план на завтра.")
        repo.create("# Встреча с командой\n\n- Обсудить прототип\n- Проверить запись с часов")
        compose.setContent { WhisperFlowTheme { androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color(0xFF0A0A0F)) { NotesScreen({}, syncOnOpen = false) } } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Идеи после прогулки").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Память приложения").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.notes_storage)).assertIsDisplayed()
        compose.onNodeWithText("Идеи после прогулки").assertIsDisplayed()
        compose.onAllNodesWithText("neo_id", substring = true).assertCountEquals(0)
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            val view = activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val target = File("build/reports/notes-list.png").apply { parentFile!!.mkdirs() }
            target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun editorIsVisualByDefaultAndSavesTheFinalKeystrokeWithoutResurrectingADraft() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repo = MarkdownNotes.reopenForTests(context)
        AudioInboxRepository.reopenForTests(context)
        val note = repo.create("# Заголовок\n\n**Важный текст**")
        var closed by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { WhisperFlowTheme { androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color(0xFF0A0A0F)) {
            if (!closed) VisualNoteEditor(note.id, synchronizationEnabled = false) { closed = true }
        } } }
        compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.neo_bold)).assertIsDisplayed()
        compose.onAllNodesWithText("neo_id", substring = true).assertCountEquals(0)
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            val view = activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val target = File("build/reports/notes-editor.png").apply { parentFile!!.mkdirs() }
            target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Первый абзац\nВторой абзац")
        val editor = compose.onNode(hasSetTextAction())
        val inputLength = editor.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.length
        editor.performTextInputSelection(androidx.compose.ui.text.TextRange(0, inputLength))
        editor.performTextInput("Последняя правка Я")
        editor.assertTextEquals("Последняя правка Я")
        editor.performTextInputSelection(androidx.compose.ui.text.TextRange(10, 16))
        editor.performTextInput("замена")
        editor.assertTextEquals("Последняя замена Я")
        editor.performTextInputSelection(androidx.compose.ui.text.TextRange(10, 16))
        editor.performTextInput("правка")
        editor.assertTextEquals("Последняя правка Я")
        compose.onNodeWithContentDescription(context.getString(R.string.notes_save)).performClick()
        compose.waitUntil(10000) { closed }
        compose.waitForIdle()
        val savedBody = MarkdownNotes.body(repo.read(note.id))
        val visibleText = com.mohamedrejeb.richeditor.model.RichTextState().setMarkdown(savedBody).annotatedString.text
        org.junit.Assert.assertTrue(savedBody, visibleText.contains("Последняя правка Я"))
        org.junit.Assert.assertNull(repo.draft(note.id))
    }
    @Test fun longPressSelectAllTrashAndPermanentDeletionWorkTogether() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repo = MarkdownNotes.reopenForTests(context)
        AudioInboxRepository.reopenForTests(context)
        repo.list().forEach { repo.delete(it.id); repo.purge(it.id) }
        repo.create("# Первая выбранная")
        repo.create("# Вторая выбранная")
        compose.setContent { WhisperFlowTheme { NotesScreen(syncOnOpen = false) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Первая выбранная").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Первая выбранная").performTouchInput { longClick() }
        compose.onNodeWithContentDescription(context.getString(R.string.neo_select_all)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.notes_delete)).performClick()
        compose.onNode(hasText(context.getString(R.string.notes_delete)) and hasClickAction()).performClick()
        compose.waitUntil(10000) { repo.list().isEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.notes_trash)).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(context.getString(R.string.neo_empty_trash)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.neo_empty_trash)).performClick()
        compose.onNode(hasText(context.getString(R.string.neo_delete_forever)) and hasClickAction()).performClick()
        compose.waitUntil(10000) { repo.list(true).filter { it.deleted }.all { !File(context.filesDir, "notes/" + it.id + ".md").exists() } }
        compose.waitUntil(10000) { runCatching { compose.onNodeWithText(context.getString(R.string.notes_trash_empty)).assertIsDisplayed() }.isSuccess }
        compose.onNodeWithText(context.getString(R.string.notes_trash_empty)).assertIsDisplayed()
    }
    @Test fun anotherNotificationForTheSameNoteCanOpenItAgain() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repo = MarkdownNotes.reopenForTests(context)
        AudioInboxRepository.reopenForTests(context)
        val note = repo.create("# Повторный переход")
        var generation by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent { WhisperFlowTheme {
            NotesScreen(syncOnOpen = false, initialNoteId = note.id, requestGeneration = generation)
        } }
        compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.notes_back)).performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { generation++ }
        compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }
}
