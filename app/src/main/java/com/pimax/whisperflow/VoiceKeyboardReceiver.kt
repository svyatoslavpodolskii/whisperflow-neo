package com.pimax.whisperflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast entry for Samsung Routines plugins, Tasker, MacroDroid, ADB:
 * adb shell am broadcast -a com.pimax.whisperflow.ACTION_VOICE_KEYBOARD -p com.pimax.whisperflow
 */
class VoiceKeyboardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != VoiceKeyboardActions.ACTION_VOICE_KEYBOARD) return
        Log.d("⌨️ VoiceKbReceiver", "Broadcast received → voice keyboard")
        VoiceKeyboardActions.trigger(context, source = "broadcast")
    }
}
