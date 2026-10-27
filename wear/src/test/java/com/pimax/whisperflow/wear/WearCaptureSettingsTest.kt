package com.pimax.whisperflow.wear

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WearCaptureSettingsTest {
    @Test fun quickCaptureIsDefaultAndUserPreferencesSurviveRecreation() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("capture_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = WearCapturePreferences(context)
        assertTrue(preferences.quickCapture)
        assertFalse(preferences.autoStop)
        preferences.quickCapture = false
        preferences.autoStop = true
        preferences.silenceSeconds = 5
        preferences.haptics = false
        val reopened = WearCapturePreferences(context)
        assertFalse(reopened.quickCapture)
        assertTrue(reopened.autoStop)
        assertEquals(5, reopened.silenceSeconds)
        assertFalse(reopened.haptics)
    }

    @Test fun assistantServiceIsProtectedAndHasSystemMetadata() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val info = context.packageManager.getServiceInfo(ComponentName(context, WearAssistantService::class.java), PackageManager.GET_META_DATA)
        assertTrue(info.exported)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", info.permission)
        assertTrue(info.metaData.getInt("android.voice_interaction") != 0)
        val session = context.packageManager.getServiceInfo(ComponentName(context, WearAssistantSessionService::class.java), 0)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", session.permission)
    }
}
