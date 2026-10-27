package com.pimax.whisperflow.wear

import android.content.Context
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
