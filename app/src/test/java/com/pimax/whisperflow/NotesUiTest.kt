package com.pimax.whisperflow

import android.content.Context
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
class NotesUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun notesListShowsHumanTitlesAndExplicitStorageLocation() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repo = MarkdownNotes.get(context)
        repo.close()
        repo.vaultUri = null
        repo.create("# Идеи после прогулки\n\nЗаписать план на завтра.")
        repo.create("# Встреча с командой\n\n- Обсудить прототип\n- Проверить запись с часов")
        compose.setContent { WhisperFlowTheme { androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color(0xFF0A0A0F)) { NotesScreen({}, syncOnOpen = false) } } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Идеи после прогулки").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Память приложения").assertIsDisplayed()
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
}
