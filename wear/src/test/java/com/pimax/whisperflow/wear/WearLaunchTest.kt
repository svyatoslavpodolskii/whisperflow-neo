package com.pimax.whisperflow.wear

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
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
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File("build/reports/watch-launch.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
