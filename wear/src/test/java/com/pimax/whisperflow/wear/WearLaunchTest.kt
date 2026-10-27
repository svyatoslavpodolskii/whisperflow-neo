package com.pimax.whisperflow.wear

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Render the actual root composable at the logical size of a 40 mm round watch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru-rRU-w192dp-h192dp-round-watch")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearLaunchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preparingControlDoesNotFlashAMicrophoneAction() {
        val context: Context = ApplicationProvider.getApplicationContext()
        compose.setContent { androidx.wear.compose.material.MaterialTheme {
            RecordButton(WatchState.STARTING, onClick = {})
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.tap_to_record)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.preparing_recording)).assertIsDisplayed()
    }

    @Test fun recordingIndicatorReactsToTheCurrentLevelAndOffersStop() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val level = androidx.compose.runtime.mutableFloatStateOf(0f)
        compose.setContent { androidx.wear.compose.material.MaterialTheme {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0A0A0F)),
                contentAlignment = androidx.compose.ui.Alignment.Center) {
                RecordButton(WatchState.RECORDING, level = { level.floatValue }, onClick = {})
            }
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.stop)).assertIsDisplayed().assertHasClickAction()
        fun render(): Bitmap {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            val root = activity.window.decorView
            return Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        var quiet: Bitmap? = null
        compose.runOnIdle { quiet = render(); level.floatValue = 1f }
        compose.waitForIdle()
        compose.runOnIdle {
            val loud = render()
            var changed = 0
            for (y in 0 until loud.height) for (x in 0 until loud.width) {
                if (quiet!!.getPixel(x, y) != loud.getPixel(x, y)) changed++
            }
            assertTrue("Current voice level must change the rendered indicator", changed > 100)
            val target = File("build/reports/watch-recording.png").apply { parentFile!!.mkdirs() }
            target.outputStream().use { loud.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun launchShowsVisibleClickableRecorderOnSmallRoundScreen() {
        val context: Context = ApplicationProvider.getApplicationContext()
        compose.setContent { WearWhisperApp(WearAudioRecorder(context)) }
        compose.onNodeWithContentDescription(context.getString(R.string.tap_to_record))
            .assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("history", ignoreCase = true).assertDoesNotExist()
        // PixelCopy requires an OS window redraw and times out under Robolectric. Draw the
        // actual measured DecorView with the native Canvas instead, after semantics settle.
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            val root = activity.window.decorView
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            var amberPixels = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                if (Color.red(pixel) > 80 && Color.red(pixel) > Color.green(pixel) && Color.green(pixel) > 20 && Color.blue(pixel) < 80) amberPixels++
            }
            assertTrue("Rendered capture control must contain amber pixels", amberPixels > 100)
            val output = File("build/reports/watch-launch.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
