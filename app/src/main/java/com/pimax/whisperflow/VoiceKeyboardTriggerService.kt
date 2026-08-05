package com.pimax.whisperflow

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** No-UI entry for Samsung side key / Routines — avoids activity task switch. */
class VoiceKeyboardTriggerService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == VoiceKeyboardActions.ACTION_VOICE_KEYBOARD) {
            VoiceKeyboardActions.trigger(this, source = "service")
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
