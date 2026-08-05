package com.pimax.whisperflow

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private const val TAG = "🔊 WhisperPTT"
private const val DOUBLE_TAP_WINDOW_MS = 450L

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WhisperAccessibilityService? = null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var voiceKeyboardOverlay: VoiceKeyboardOverlay? = null
    private var isPttActive = false
    private var lastPowerDownTime = 0L
    private var lastVolumeDownTime = 0L
    private lateinit var settings: SettingsDataStore

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FocusTextInjector.accessibilityService = this
        settings = SettingsDataStore(applicationContext)
        Log.d(TAG, "Accessibility connected — Volume PTT + voice keyboard triggers")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // POWER double-tap: blocked on most Samsung devices — kept as best-effort only
        if (keyCode == KeyEvent.KEYCODE_POWER && action == KeyEvent.ACTION_DOWN) {
            val voiceKeyboardEnabled = runBlocking { settings.voiceKeyboardMode.first() }
            if (voiceKeyboardEnabled) {
                val now = System.currentTimeMillis()
                if (now - lastPowerDownTime < DOUBLE_TAP_WINDOW_MS) {
                    lastPowerDownTime = 0L
                    Log.d(TAG, "Double-tap POWER detected → voice keyboard")
                    triggerVoiceKeyboardSession()
                    return true
                }
                lastPowerDownTime = now
                Log.d(TAG, "POWER down (waiting for double-tap window)")
            }
            return false
        }

        val voiceKeyboardEnabled = runBlocking { settings.voiceKeyboardMode.first() }

        // Volume double-tap fallback when voice keyboard mode is on
        if (voiceKeyboardEnabled && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeDownTime < DOUBLE_TAP_WINDOW_MS) {
                lastVolumeDownTime = 0L
                Log.d(TAG, "Double-tap VOLUME DOWN → voice keyboard")
                triggerVoiceKeyboardSession()
                return true
            }
            lastVolumeDownTime = now
        }

        val isEnabled = runBlocking { settings.volumePttEnabled.first() }
        if (!isEnabled) return false

        val configuredKeyCode = runBlocking { settings.volumePttKeyCode.first() }.toIntOrNull()
            ?: KeyEvent.KEYCODE_VOLUME_DOWN

        if (keyCode == configuredKeyCode) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (!isPttActive) {
                    isPttActive = true
                    Log.d(TAG, "PTT START: Key $keyCode Pressed")
                    val intent = Intent(this, WhisperAssistService::class.java).apply {
                        setAction("com.pimax.whisperflow.ACTION_PTT_START")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startService(intent)
                }
                return true
            } else if (action == KeyEvent.ACTION_UP) {
                if (isPttActive) {
                    isPttActive = false
                    Log.d(TAG, "PTT STOP: Key $keyCode Released")
                    val intent = Intent("com.pimax.whisperflow.ACTION_PTT_STOP")
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                }
                return true
            }
        }

        return super.onKeyEvent(event)
    }

    private fun triggerVoiceKeyboardSession() {
        Log.d(TAG, "triggerVoiceKeyboardSession — launching VoiceKeyboardTriggerActivity")
        val intent = Intent(this, VoiceKeyboardTriggerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    fun showVoiceKeyboardOverlay() {
        if (voiceKeyboardOverlay == null) {
            voiceKeyboardOverlay = VoiceKeyboardOverlay(this)
        }
        voiceKeyboardOverlay?.show()
    }

    fun stopVoiceKeyboardOverlay() {
        voiceKeyboardOverlay?.stopIfRecording()
    }

    override fun onDestroy() {
        voiceKeyboardOverlay?.destroy()
        voiceKeyboardOverlay = null
        super.onDestroy()
        instance = null
        FocusTextInjector.accessibilityService = null
        serviceScope.cancel()
    }
}
