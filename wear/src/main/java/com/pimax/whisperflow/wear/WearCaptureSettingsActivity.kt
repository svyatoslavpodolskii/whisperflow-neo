package com.pimax.whisperflow.wear

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn

class WearCaptureSettingsActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.pimax.whisperflow.sync.UiLanguage.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = WearCapturePreferences(this)
        setContent {
            var quick by remember { mutableStateOf(preferences.quickCapture) }
            var auto by remember { mutableStateOf(preferences.autoStop) }
            var silence by remember { mutableIntStateOf(preferences.silenceSeconds) }
            var haptics by remember { mutableStateOf(preferences.haptics) }
            var assistantError by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
            MaterialTheme {
                ScalingLazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(vertical = 32.dp)) {
                    item { Text(stringResource(R.string.capture_settings)) }
                    item { Chip(onClick = {
                        val language = com.pimax.whisperflow.sync.UiLanguage.code(this@WearCaptureSettingsActivity)
                        com.pimax.whisperflow.sync.UiLanguage.set(this@WearCaptureSettingsActivity, if (language == "ru") "en" else "ru")
                        recreate()
                    }, label = { Text(stringResource(R.string.interface_language)) }) }
                    item { ToggleChip(checked = quick, onCheckedChange = { quick = it; preferences.quickCapture = it }, label = { Text(stringResource(R.string.quick_capture)) }, toggleControl = { Switch(checked = quick) }) }
                    item { ToggleChip(checked = auto, onCheckedChange = { auto = it; preferences.autoStop = it }, label = { Text(stringResource(R.string.auto_stop)) }, toggleControl = { Switch(checked = auto) }) }
                    item { Chip(onClick = { silence = when (silence) { 2 -> 3; 3 -> 5; else -> 2 }; preferences.silenceSeconds = silence }, label = { Text(stringResource(R.string.silence_seconds, silence)) }) }
                    item { ToggleChip(checked = haptics, onCheckedChange = { haptics = it; preferences.haptics = it }, label = { Text(stringResource(R.string.haptics)) }, toggleControl = { Switch(checked = haptics) }) }
                    item { Chip(onClick = {
                        val manager = getSystemService(RoleManager::class.java)
                        try {
                            if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) launcher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                            else { assistantError = true; launcher.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                        } catch (_: Exception) { assistantError = true }
                    }, label = { Text(stringResource(R.string.choose_assistant)) }) }
                    if (assistantError) item { Text(stringResource(R.string.assistant_unavailable)) }
                }
            }
        }
    }
}
