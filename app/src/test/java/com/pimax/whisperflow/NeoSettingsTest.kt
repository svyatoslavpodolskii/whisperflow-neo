package com.pimax.whisperflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pimax.whisperflow.local.ModelManager.WhisperModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeoSettingsTest {
    @Test fun livePreferenceSurvivesGatewayRoundTripAndReopening() = runBlocking {
        org.junit.Assume.assumeFalse("DataStore 1.0 File.renameTo replacement requires POSIX; run the persistence cases in Linux CI", System.getProperty("os.name", "").orEmpty().startsWith("Windows"))
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = SettingsDataStore(context)
        store.updateLanguage("Russian")
        store.updateLiveChunkingEnabled(true)
        store.updateInferenceMode("LLM_GATEWAY")
        assertTrue(store.liveChunkingEnabled.first())
        assertFalse(liveTranscriptionEnabled(store.liveChunkingEnabled.first(), store.inferenceMode.first()))
        store.updateInferenceMode("LOCAL")
        assertTrue(liveTranscriptionEnabled(store.liveChunkingEnabled.first(), store.inferenceMode.first()))
        store.updateLiveChunkingEnabled(false)
        val reopened = SettingsDataStore(context)
        assertFalse(reopened.liveChunkingEnabled.first())
        assertEquals("Russian", reopened.language.first())
    }
    @Test fun cleanupRequiresGatewayAndStatusNotificationsAreIndependent() = runBlocking {
        org.junit.Assume.assumeFalse("DataStore 1.0 File.renameTo replacement requires POSIX; run the persistence cases in Linux CI", System.getProperty("os.name", "").orEmpty().startsWith("Windows"))
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = SettingsDataStore(context)
        store.updateInferenceMode("LOCAL"); store.updateRamblerEnabled(true)
        assertFalse(store.ramblerEnabled.first())
        store.updateInferenceMode("LLM_GATEWAY"); store.updateRamblerEnabled(true)
        assertTrue(store.ramblerEnabled.first())
        store.updateInferenceMode("LOCAL")
        assertFalse(store.ramblerEnabled.first())
        store.updateShowTranscriptionStatus(false); store.updateNotifyUnreadNotes(true)
        val reopened = SettingsDataStore(context)
        assertFalse(reopened.showTranscriptionStatus.first())
        assertTrue(reopened.notifyUnreadNotes.first())
    }
    @Test fun modelRecommendationRespectsLanguageAndMemoryWithoutChangingSelections() {
        assertEquals(WhisperModel.TINY, recommendedSpeechModel("English", 256))
        assertEquals(WhisperModel.BASE, recommendedSpeechModel("Russian", 256))
        assertEquals(WhisperModel.SMALL, recommendedSpeechModel("Russian", 1024))
        assertEquals(WhisperModel.TINY, recommendedSpeechModel("Auto", 128))
    }
    @Test fun emptyAndInvalidServicesNeverBecomeRelativeWhoamiUrls() = runBlocking {
        assertFalse(validServiceUrl(""))
        assertFalse(validServiceUrl("localhost:8100"))
        assertEquals("", authUrlFromTasksUrl(""))
        assertEquals("https://example.test:8100", authUrlFromTasksUrl("https://example.test:8058"))
        assertEquals("http://[::1]:8058", deriveServiceUrl("http://[::1]:8060", 8058))
        assertTrue(PimaxAuthApi("").whoami().error!!.contains("Не настроен"))
    }
}
