package com.pimax.whisperflow

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "whisperflow_settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val LANGUAGE_KEY = stringPreferencesKey("language")
        private val TASK_KEY = stringPreferencesKey("task")
        private val LAST_TRANSCRIPTION_KEY = stringPreferencesKey("last_transcription")
        private val LAST_TRANSCRIPTION_TIME_KEY = longPreferencesKey("last_transcription_time")
        private val HISTORY_KEY = stringPreferencesKey("history")
        private val INFERENCE_MODE_KEY = stringPreferencesKey("inference_mode")
        private val LOCAL_MODEL_KEY = stringPreferencesKey("local_model")
        private val GATEWAY_URL_KEY = stringPreferencesKey("gateway_url")

        // Tasks connection settings
        private val TASKS_ENABLED_KEY = stringPreferencesKey("tasks_enabled")
        private val TASKS_URL_KEY = stringPreferencesKey("tasks_url")

        // Streak Tracker connection settings
        private val STREAK_ENABLED_KEY = stringPreferencesKey("streak_enabled")
        private val STREAK_URL_KEY = stringPreferencesKey("streak_url")

        // Voice Assistant connection settings
        private val ASSISTANT_ENABLED_KEY = stringPreferencesKey("assistant_enabled")
        private val ASSISTANT_URL_KEY = stringPreferencesKey("assistant_url")

        // Assistant settings
        private val ASSISTANT_STOP_MODE_KEY = stringPreferencesKey("assistant_stop_mode")
        private val ASSISTANT_ANIMATION_MODE_KEY = stringPreferencesKey("assistant_animation_mode")
        val VOLUME_PTT_ENABLED_KEY = booleanPreferencesKey("volume_ptt_enabled")
        private val VOLUME_PTT_KEY_CODE_KEY = stringPreferencesKey("volume_ptt_key_code")
        val ASSISTANT_ANIMATION_SPEED_KEY = floatPreferencesKey("assistant_animation_speed")

        // Sound and Vibration settings
        val SOUND_ENABLED_KEY = booleanPreferencesKey("sound_enabled")
        val VIBRATION_ENABLED_KEY = booleanPreferencesKey("vibration_enabled")
        val VIBRATION_INTENSITY_KEY = floatPreferencesKey("vibration_intensity")
        val RAMBLER_ENABLED_KEY = booleanPreferencesKey("rambler_enabled")
        val VOICE_KEYBOARD_MODE_KEY = booleanPreferencesKey("voice_keyboard_mode")
        val VOICE_KEYBOARD_SESSION_PENDING_KEY = booleanPreferencesKey("voice_keyboard_session_pending")

        // Transcription tuning settings
        private val CHUNK_INTERVAL_KEY = stringPreferencesKey("chunk_interval_ms")
        private val SILENCE_TIMEOUT_KEY = stringPreferencesKey("silence_timeout_ms")
        private val HALLUCINATION_THRESHOLD_KEY = stringPreferencesKey("hallucination_threshold")
        private val TOAST_TEMPLATE_KEY = stringPreferencesKey("toast_template")
        private val LIVE_CHUNKING_ENABLED_KEY = stringPreferencesKey("live_chunking_enabled")
        private val SERVER_ENABLED_KEY = stringPreferencesKey("local_server_enabled")
        private val SERVER_PORT_KEY = stringPreferencesKey("local_server_port")

        const val DEFAULT_SERVER_URL = ""
        const val DEFAULT_LANGUAGE = "Auto"
        const val DEFAULT_TASK = "transcribe"
        const val DEFAULT_INFERENCE_MODE = "GPU_SERVER"
        const val DEFAULT_LOCAL_MODEL = "TINY"
        const val DEFAULT_TASKS_URL = ""
        const val DEFAULT_STREAK_URL = ""
        const val DEFAULT_ASSISTANT_URL = ""
        const val DEFAULT_GATEWAY_URL = ""

        // Transcription tuning defaults
        const val DEFAULT_CHUNK_INTERVAL_MS = 1800L
        const val DEFAULT_SILENCE_TIMEOUT_MS = 1800L
        const val DEFAULT_HALLUCINATION_THRESHOLD = 0.15f
        const val DEFAULT_TOAST_TEMPLATE = "✅ Copied: {text}"
        const val DEFAULT_SERVER_ENABLED = false
        const val DEFAULT_SERVER_PORT = 8060

        // Assistant stop modes: "silence" (auto-stop on silence) or "manual" (stop on power button)
        const val ASSISTANT_STOP_SILENCE = "silence"
        const val ASSISTANT_STOP_MANUAL = "manual"
        const val DEFAULT_ASSISTANT_STOP_MODE = ASSISTANT_STOP_SILENCE

        const val ANIMATION_MODE_SNAKE = "SNAKE"
        const val ANIMATION_MODE_TREE = "TREE"
        const val ANIMATION_MODE_CARD = "CARD"
        const val DEFAULT_ANIMATION_MODE = ANIMATION_MODE_SNAKE

        const val DEFAULT_VOLUME_PTT_KEY_CODE = "25" // KEYCODE_VOLUME_DOWN
        const val DEFAULT_RAMBLER_ENABLED = false
        const val DEFAULT_VOICE_KEYBOARD_MODE = false

    }

    val serverUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL_KEY] ?: DEFAULT_SERVER_URL
    }

    suspend fun updateServerUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = url
        }
    }

    val language: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: DEFAULT_LANGUAGE
    }

    suspend fun updateLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language
        }
    }

    val task: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[TASK_KEY] ?: DEFAULT_TASK
    }

    suspend fun updateTask(task: String) {
        context.dataStore.edit { preferences ->
            preferences[TASK_KEY] = task
        }
    }

    // Inference mode: GPU_SERVER or LOCAL
    val inferenceMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[INFERENCE_MODE_KEY] ?: DEFAULT_INFERENCE_MODE
    }

    suspend fun updateInferenceMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[INFERENCE_MODE_KEY] = mode
        }
    }

    // Local model: TINY, BASE, or SMALL
    val localModel: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LOCAL_MODEL_KEY] ?: DEFAULT_LOCAL_MODEL
    }

    suspend fun updateLocalModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[LOCAL_MODEL_KEY] = model
        }
    }

    val gatewayUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[GATEWAY_URL_KEY] ?: DEFAULT_GATEWAY_URL
    }

    suspend fun updateGatewayUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[GATEWAY_URL_KEY] = url
        }
    }

    // Tasks connection: enabled/disabled
    val tasksEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TASKS_ENABLED_KEY] == "true"
    }

    suspend fun updateTasksEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TASKS_ENABLED_KEY] = if (enabled) "true" else "false"
        }
    }

    // Tasks connection: server URL
    val tasksUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[TASKS_URL_KEY] ?: DEFAULT_TASKS_URL
    }

    suspend fun updateTasksUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[TASKS_URL_KEY] = url
        }
    }

    // Assistant stop mode: "silence" or "manual"
    val assistantStopMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[ASSISTANT_STOP_MODE_KEY] ?: DEFAULT_ASSISTANT_STOP_MODE
    }

    suspend fun updateAssistantStopMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[ASSISTANT_STOP_MODE_KEY] = mode
        }
    }

    // Assistant animation mode: "SNAKE" or "TREE"
    val assistantAnimationMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[ASSISTANT_ANIMATION_MODE_KEY] ?: DEFAULT_ANIMATION_MODE
    }

    suspend fun updateAssistantAnimationMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[ASSISTANT_ANIMATION_MODE_KEY] = mode
        }
    }

    val volumePttEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[VOLUME_PTT_ENABLED_KEY] ?: true
    }

    suspend fun updateVolumePttEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VOLUME_PTT_ENABLED_KEY] = enabled
        }
    }

    val volumePttKeyCode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[VOLUME_PTT_KEY_CODE_KEY] ?: DEFAULT_VOLUME_PTT_KEY_CODE
    }

    suspend fun updateVolumePttKeyCode(keyCode: String) {
        context.dataStore.edit { preferences ->
            preferences[VOLUME_PTT_KEY_CODE_KEY] = keyCode
        }
    }

    val assistantAnimationSpeed: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[ASSISTANT_ANIMATION_SPEED_KEY] ?: 1.0f
    }

    suspend fun updateAssistantAnimationSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[ASSISTANT_ANIMATION_SPEED_KEY] = speed
        }
    }

    val soundEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SOUND_ENABLED_KEY] ?: true
    }

    suspend fun updateSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SOUND_ENABLED_KEY] = enabled
        }
    }

    val vibrationEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[VIBRATION_ENABLED_KEY] ?: true
    }

    suspend fun updateVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VIBRATION_ENABLED_KEY] = enabled
        }
    }

    val vibrationIntensity: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[VIBRATION_INTENSITY_KEY] ?: 0.8f
    }

    suspend fun updateVibrationIntensity(intensity: Float) {
        context.dataStore.edit { preferences ->
            preferences[VIBRATION_INTENSITY_KEY] = intensity
        }
    }

    val ramblerEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[RAMBLER_ENABLED_KEY] ?: DEFAULT_RAMBLER_ENABLED
    }

    suspend fun updateRamblerEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[RAMBLER_ENABLED_KEY] = enabled
        }
    }

    val voiceKeyboardMode: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[VOICE_KEYBOARD_MODE_KEY] ?: DEFAULT_VOICE_KEYBOARD_MODE
    }

    suspend fun updateVoiceKeyboardMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_KEYBOARD_MODE_KEY] = enabled
        }
    }

    suspend fun setVoiceKeyboardSessionPending(pending: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_KEYBOARD_SESSION_PENDING_KEY] = pending
        }
    }

    suspend fun consumeVoiceKeyboardSessionPending(): Boolean {
        var pending = false
        context.dataStore.edit { preferences ->
            pending = preferences[VOICE_KEYBOARD_SESSION_PENDING_KEY] == true
            preferences.remove(VOICE_KEYBOARD_SESSION_PENDING_KEY)
        }
        return pending
    }


    // Streak Tracker connection: enabled/disabled
    val streakEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[STREAK_ENABLED_KEY] == "true"
    }

    suspend fun updateStreakEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STREAK_ENABLED_KEY] = if (enabled) "true" else "false"
        }
    }

    // Streak Tracker connection: server URL
    val streakUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[STREAK_URL_KEY] ?: DEFAULT_STREAK_URL
    }

    suspend fun updateStreakUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[STREAK_URL_KEY] = url
        }
    }

    val assistantEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[ASSISTANT_ENABLED_KEY] == "true"
    }

    suspend fun updateAssistantEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ASSISTANT_ENABLED_KEY] = if (enabled) "true" else "false"
        }
    }

    val assistantUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[ASSISTANT_URL_KEY] ?: DEFAULT_ASSISTANT_URL
    }

    suspend fun updateAssistantUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[ASSISTANT_URL_KEY] = url
        }
    }

    // Transcription tuning: chunk interval
    val chunkIntervalMs: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[CHUNK_INTERVAL_KEY]?.toLongOrNull() ?: DEFAULT_CHUNK_INTERVAL_MS
    }
    suspend fun updateChunkIntervalMs(ms: Long) {
        context.dataStore.edit { it[CHUNK_INTERVAL_KEY] = ms.toString() }
    }

    // Transcription tuning: silence timeout
    val silenceTimeoutMs: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[SILENCE_TIMEOUT_KEY]?.toLongOrNull() ?: DEFAULT_SILENCE_TIMEOUT_MS
    }
    suspend fun updateSilenceTimeoutMs(ms: Long) {
        context.dataStore.edit { it[SILENCE_TIMEOUT_KEY] = ms.toString() }
    }

    // Transcription tuning: hallucination filter threshold
    val hallucinationThreshold: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[HALLUCINATION_THRESHOLD_KEY]?.toFloatOrNull() ?: DEFAULT_HALLUCINATION_THRESHOLD
    }
    suspend fun updateHallucinationThreshold(threshold: Float) {
        context.dataStore.edit { it[HALLUCINATION_THRESHOLD_KEY] = threshold.toString() }
    }

    // Transcription tuning: toast template
    val toastTemplate: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[TOAST_TEMPLATE_KEY] ?: DEFAULT_TOAST_TEMPLATE
    }
    suspend fun updateToastTemplate(template: String) {
        context.dataStore.edit { it[TOAST_TEMPLATE_KEY] = template }
    }

    val liveChunkingEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LIVE_CHUNKING_ENABLED_KEY] != "false" // Default to true
    }

    suspend fun updateLiveChunkingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LIVE_CHUNKING_ENABLED_KEY] = if (enabled) "true" else "false"
        }
    }

    val localServerEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SERVER_ENABLED_KEY] == "true"
    }

    suspend fun updateLocalServerEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_ENABLED_KEY] = if (enabled) "true" else "false"
        }
    }

    val localServerPort: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[SERVER_PORT_KEY]?.toIntOrNull() ?: DEFAULT_SERVER_PORT
    }

    suspend fun updateLocalServerPort(port: Int) {
        context.dataStore.edit { it[SERVER_PORT_KEY] = port.toString() }
    }

    val lastTranscription: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LAST_TRANSCRIPTION_KEY] ?: ""
    }

    suspend fun updateLastTranscription(text: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_TRANSCRIPTION_KEY] = text
            preferences[LAST_TRANSCRIPTION_TIME_KEY] = System.currentTimeMillis()
        }
    }

    val lastTranscriptionTime: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[LAST_TRANSCRIPTION_TIME_KEY] ?: 0L
    }

    val history: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[HISTORY_KEY] ?: "[]"
    }

    /**
     * Copy a WAV file from cache to permanent storage: files/recordings/whisper_<timestamp>.wav
     * Returns the permanent file path, or null on failure.
     */
    fun saveRecordingPermanently(sourceWav: File): String? {
        return try {
            val recordingsDir = File(context.filesDir, "recordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val destFile = File(recordingsDir, "whisper_$timestamp.wav")
            sourceWav.copyTo(destFile, overwrite = true)
            Log.d("SettingsDataStore", "Recording saved: ${destFile.absolutePath} (${destFile.length()} bytes)")
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e("SettingsDataStore", "Failed to save recording: ${e.message}")
            null
        }
    }

    suspend fun addToHistory(text: String, durationMs: Long, language: String?, audioFilePath: String? = null, mode: String? = null, sentToTasks: Boolean = false) {
        context.dataStore.edit { preferences ->
            val existing = preferences[HISTORY_KEY] ?: "[]"
            try {
                val array = org.json.JSONArray(existing)
                val entry = org.json.JSONObject().apply {
                    put("text", text)
                    put("timestamp", System.currentTimeMillis())
                    put("duration_ms", durationMs)
                    if (language != null) put("language", language)
                    if (audioFilePath != null) put("audio_file", audioFilePath)
                    if (mode != null) put("mode", mode)
                    if (sentToTasks) put("sent_to_tasks", true)
                }
                array.put(entry)

                while (array.length() > 50) {
                    array.remove(0)
                }

                preferences[HISTORY_KEY] = array.toString()
            } catch (_: Exception) {
                val fresh = org.json.JSONArray()
                val entry = org.json.JSONObject().apply {
                    put("text", text)
                    put("timestamp", System.currentTimeMillis())
                    put("duration_ms", durationMs)
                    if (language != null) put("language", language)
                    if (audioFilePath != null) put("audio_file", audioFilePath)
                    if (mode != null) put("mode", mode)
                    if (sentToTasks) put("sent_to_tasks", true)
                }
                fresh.put(entry)
                preferences[HISTORY_KEY] = fresh.toString()
            }
        }
    }

    suspend fun deleteHistoryEntry(timestamp: Long) {
        context.dataStore.edit { preferences ->
            val existing = preferences[HISTORY_KEY] ?: "[]"
            try {
                val array = org.json.JSONArray(existing)
                val newArray = org.json.JSONArray()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.getLong("timestamp") != timestamp) {
                        newArray.put(obj)
                    } else {
                        // Delete associated audio file if exists
                        if (obj.has("audio_file")) {
                            try {
                                val audioPath = obj.getString("audio_file")
                                val audioFile = java.io.File(audioPath)
                                if (audioFile.exists()) {
                                    audioFile.delete()
                                    Log.d("SettingsDataStore", "Deleted audio file: $audioPath")
                                }
                            } catch (e: Exception) {
                                Log.e("SettingsDataStore", "Failed to delete audio file: ${e.message}")
                            }
                        }
                    }
                }
                preferences[HISTORY_KEY] = newArray.toString()
            } catch (e: Exception) {
                Log.e("SettingsDataStore", "Failed to delete history entry: ${e.message}")
            }
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { preferences ->
            preferences[HISTORY_KEY] = "[]"
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
