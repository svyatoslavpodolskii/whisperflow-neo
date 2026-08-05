package com.pimax.whisperflow.wear

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wear_settings")

/**
 * Watch-side settings storage.
 *
 * Stores:
 * - Inference mode (relay via phone vs direct GPU)
 * - GPU server URL for direct mode
 * - Language preference
 */
class WearSettings(private val context: Context) {

    companion object {
        // Keys
        private val KEY_USE_DIRECT_GPU = booleanPreferencesKey("use_direct_gpu")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_LANGUAGE = stringPreferencesKey("language")

        // Defaults
        const val DEFAULT_SERVER_URL = ""
        const val DEFAULT_LANGUAGE = "Auto"
        const val DEFAULT_USE_DIRECT_GPU = true
    }

    /** Whether to call GPU server directly (true) or relay via phone (false) */
    val useDirectGpu: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_USE_DIRECT_GPU] ?: DEFAULT_USE_DIRECT_GPU
    }

    /** GPU server URL for direct mode */
    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    /** Language for transcription */
    val language: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE
    }

    /** Set direct GPU mode on/off */
    suspend fun setUseDirectGpu(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USE_DIRECT_GPU] = value
        }
    }

    /** Set GPU server URL */
    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = url
        }
    }

    /** Set language */
    suspend fun setLanguage(lang: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = lang
        }
    }
}

/** Inference mode enum for clearer code */
enum class WearInferenceMode {
    /** Send audio to phone, phone transcribes via GPU */
    PHONE_RELAY,

    /** Call GPU server directly from watch (requires WiFi) */
    DIRECT_GPU
}
