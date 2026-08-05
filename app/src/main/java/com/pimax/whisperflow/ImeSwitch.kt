package com.pimax.whisperflow

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Programmatic IME switching for voice keyboard overlay.
 *
 * Requires WRITE_SECURE_SETTINGS (granted via ADB once):
 *   adb shell pm grant com.pimax.whisperflow android.permission.WRITE_SECURE_SETTINGS
 *
 * When WhisperFlow is the active IME, WhisperInputBridge.commitComposedText()
 * bypasses accessibility SET_TEXT entirely — works on WhatsApp, Chrome, etc.
 */
object ImeSwitch {
    private const val TAG = "⌨️ ImeSwitch"
    private const val WHISPERFLOW_IME = "com.pimax.whisperflow/.WhisperInputService"

    @Volatile
    var previousIme: String? = null
        private set

    /**
     * Switches the system default IME to WhisperFlow.
     * Saves the current IME so it can be restored later.
     * Returns true if the switch succeeded.
     */
    fun switchToWhisperFlow(context: Context): Boolean {
        return try {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            if (current == WHISPERFLOW_IME) {
                Log.d(TAG, "Already on WhisperFlow IME")
                previousIme = current
                return true
            }
            previousIme = current
            val ok = Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                WHISPERFLOW_IME
            )
            if (ok) {
                Log.d(TAG, "Switched IME: $current → $WHISPERFLOW_IME")
            } else {
                Log.w(TAG, "putString returned false — WRITE_SECURE_SETTINGS missing?")
            }
            ok
        } catch (e: SecurityException) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS not granted: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "IME switch failed: ${e.message}")
            false
        }
    }

    /**
     * Restores the previously saved IME. No-op if nothing was saved.
     */
    fun restorePreviousIme(context: Context) {
        val prev = previousIme ?: return
        if (prev == WHISPERFLOW_IME) return
        try {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            if (current != WHISPERFLOW_IME) {
                Log.d(TAG, "IME already changed away from WhisperFlow — skipping restore")
                previousIme = null
                return
            }
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                prev
            )
            Log.d(TAG, "Restored IME: $WHISPERFLOW_IME → $prev")
        } catch (e: Exception) {
            Log.w(TAG, "IME restore failed: ${e.message}")
        }
        previousIme = null
    }

    /**
     * Returns true if WRITE_SECURE_SETTINGS is available.
     */
    fun canSwitchIme(context: Context): Boolean {
        return try {
            Settings.Secure.putString(
                context.contentResolver,
                "whisperflow_test_key",
                null
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
