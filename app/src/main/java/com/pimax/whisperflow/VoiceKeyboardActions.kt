package com.pimax.whisperflow

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Samsung Side key, Routines, launcher shortcuts, Tasker, and ADB can trigger this action.
 *
 * Side key path (Activity): switches to WhisperFlow IME + auto-starts recording.
 * Broadcast/volume path: uses accessibility overlay (legacy).
 */
object VoiceKeyboardActions {
    private const val TAG = "⌨️ VoiceKeyboard"
    private const val DEBOUNCE_MS = 1200L

    const val ACTION_VOICE_KEYBOARD = "com.pimax.whisperflow.ACTION_VOICE_KEYBOARD"
    const val ACTION_VOICE_KEYBOARD_START = "com.pimax.whisperflow.ACTION_VOICE_KEYBOARD_START"

    @Volatile
    var lastTriggerMs = 0L

    /** Set by side key trigger — WhisperInputService checks this in onStartInputView. */
    @Volatile
    var pendingAutoRecord = false

    /** True while the current IME recording session was started by side key. */
    @Volatile
    var wasAutoTriggered = false

    fun wasRecentlyTriggered(withinMs: Long = 2500L): Boolean {
        return System.currentTimeMillis() - lastTriggerMs < withinMs
    }

    /**
     * Legacy overlay trigger — now redirected to the main IME voice keyboard action.
     * Triggers the IME switch and toggles recording on/off.
     */
    fun trigger(context: Context, source: String = "unknown") {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < DEBOUNCE_MS) {
            Log.d(TAG, "Debounced duplicate trigger from $source")
            return
        }
        lastTriggerMs = now
        Log.d(TAG, "Triggering voice keyboard from $source — launching VoiceKeyboardTriggerActivity")

        val intent = Intent(context, VoiceKeyboardTriggerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
