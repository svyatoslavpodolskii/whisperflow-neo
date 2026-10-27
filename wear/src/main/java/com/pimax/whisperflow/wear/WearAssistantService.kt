package com.pimax.whisperflow.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Assistant is an entry point only. It never owns an always-on microphone or a model. */
class WearAssistantService : VoiceInteractionService()

class WearAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = WearCaptureSession(this)
}

private class WearCaptureSession(private val context: Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(Intent(context, WearMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        hide()
    }
}
