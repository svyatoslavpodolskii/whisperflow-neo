package com.pimax.whisperflow

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

private const val TAG = "⌨️ VoiceKbTrigger"
private const val WHISPERFLOW_IME = "com.pimax.whisperflow/.WhisperInputService"

/**
 * Invisible trampoline for Samsung Side key → App action.
 *
 * Toggle behavior:
 *   - 1st press: switch to WhisperFlow IME → auto-start recording
 *   - 2nd press: stop recording → restore previous IME
 *
 * No overlay, no task switch, no home screen flash.
 * The WhisperFlow keyboard's bottom bar (mic, waveform, live preview) is the UI.
 */
class VoiceKeyboardTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Make window completely invisible BEFORE super draws anything
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        super.onCreate(savedInstanceState)

        // Debounce rapid presses
        val now = System.currentTimeMillis()
        if (now - VoiceKeyboardActions.lastTriggerMs < 800L) {
            Log.d(TAG, "Debounced rapid press")
            bail()
            return
        }
        VoiceKeyboardActions.lastTriggerMs = now

        val isWhisperFlowActive = isWhisperFlowActiveIme()
        val ime = WhisperInputBridge.activeIme

        if (isWhisperFlowActive && ime != null && ime.isCurrentlyRecording()) {
            // ── TOGGLE OFF ─────────────────────────────────────────────
            Log.d(TAG, "Toggle OFF — stopping recording, restoring IME")
            ime.stopRecordingExternal()
            VoiceKeyboardActions.wasAutoTriggered = false
            // Restore previous keyboard after text commits
            Handler(Looper.getMainLooper()).postDelayed({
                ImeSwitch.restorePreviousIme(applicationContext)
            }, 800)
        } else {
            // ── TOGGLE ON ──────────────────────────────────────────────
            Log.d(TAG, "Toggle ON — switching to WhisperFlow IME + auto-record")

            if (!isWhisperFlowActive) {
                val switched = ImeSwitch.switchToWhisperFlow(this)
                if (!switched) {
                    Log.e(TAG, "IME switch failed — WRITE_SECURE_SETTINGS missing?")
                    bail()
                    return
                }
            }

            VoiceKeyboardActions.pendingAutoRecord = true
            VoiceKeyboardActions.wasAutoTriggered = true

            // If IME is already bound and ready, start recording immediately
            if (ime != null && !ime.isCurrentlyRecording()) {
                ime.startRecordingExternal()
            }
            // Otherwise, onStartInputView will pick up pendingAutoRecord
        }

        bail()
    }

    private fun isWhisperFlowActiveIme(): Boolean {
        val current = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        return current == WHISPERFLOW_IME
    }

    private fun restorePreviousApp() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(10)
            if (tasks != null && tasks.size > 1) {
                val prevTask = tasks[1]
                Log.d(TAG, "Moving task ${prevTask.id} (${prevTask.topActivity?.packageName}) to front")
                am.moveTaskToFront(prevTask.id, ActivityManager.MOVE_TASK_WITH_HOME)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore previous app: ${e.message}")
        }
    }

    private fun bail() {
        restorePreviousApp()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
