package com.pimax.whisperflow.wear

import android.content.Context

class WearCapturePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("capture_preferences", Context.MODE_PRIVATE)
    var quickCapture: Boolean
        get() = prefs.getBoolean("quick_capture", true)
        set(value) { prefs.edit().putBoolean("quick_capture", value).apply() }
    var autoStop: Boolean
        get() = prefs.getBoolean("auto_stop", false)
        set(value) { prefs.edit().putBoolean("auto_stop", value).apply() }
    var silenceSeconds: Int
        get() = prefs.getInt("silence_seconds", 3)
        set(value) { require(value in listOf(2, 3, 5)); prefs.edit().putInt("silence_seconds", value).apply() }
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) { prefs.edit().putBoolean("haptics", value).apply() }
}
