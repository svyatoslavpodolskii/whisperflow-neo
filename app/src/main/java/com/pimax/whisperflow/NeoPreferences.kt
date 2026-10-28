package com.pimax.whisperflow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pimax.whisperflow.local.ModelManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.*

fun liveTranscriptionEnabled(requested: Boolean, mode: String) = requested && mode != "LLM_GATEWAY"
fun validServiceUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value.trim())
    uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)

internal suspend fun clipboardToast(context: Context, text: String): String =
    SettingsDataStore(context).toastTemplate.first().replace("{text}", text)

fun recommendedSpeechModel(language: String, heapMb: Long): ModelManager.WhisperModel = when {
    heapMb >= 768 -> ModelManager.WhisperModel.SMALL
    heapMb >= 256 && language !in listOf("English", "en") -> ModelManager.WhisperModel.BASE
    heapMb >= 512 -> ModelManager.WhisperModel.BASE
    else -> ModelManager.WhisperModel.TINY
}

@Composable internal fun RequestNoteNotificationPermission(microphoneGranted: Boolean) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(microphoneGranted) {
        if (!microphoneGranted || Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val settings = SettingsDataStore(context)
        val wanted = settings.showTranscriptionStatus.first() || settings.notifyUnreadNotes.first()
        val preferences = context.getSharedPreferences("neo_permissions", Context.MODE_PRIVATE)
        if (wanted && !preferences.getBoolean("notifications_asked", false) &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            preferences.edit().putBoolean("notifications_asked", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable fun FeatureHelp(id: Int) {
    Text(stringResource(id), color = Color(0xFFB8B2BC), fontSize = 12.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(12.dp))
}

@Composable fun ModelRecommendation(language: String) {
    val model = recommendedSpeechModel(language, Runtime.getRuntime().maxMemory() / (1024 * 1024))
    Text(stringResource(R.string.neo_model_recommendation, model.displayName), color = Color(0xFFFFAD42), fontSize = 12.sp, lineHeight = 18.sp)
}

@Composable fun NeoPreferencesPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsDataStore(context) }
    val status by remember { settings.showTranscriptionStatus }.collectAsState(initial = true)
    val unread by remember { settings.notifyUnreadNotes }.collectAsState(initial = false)
    val visualization by remember { settings.phoneVisualization }.collectAsState(initial = "BARS")
    val notes = remember { MarkdownNotes.get(context) }
    var days by remember { mutableIntStateOf(notes.retentionDays) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    SettingsCard(stringResource(R.string.neo_behavior)) {
        Text(stringResource(R.string.neo_phone_visual), color = Color(0xFFF5F0EB), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("BARS" to R.string.neo_bars, "WAVE" to R.string.neo_wave, "ORBIT" to R.string.neo_orbit).forEach { (key, label) ->
                FilterChip(selected = visualization == key, onClick = { scope.launch { settings.updatePhoneVisualization(key) } },
                    label = { Text(stringResource(label)) })
            }
        }
        PhoneCaptureVisualizer(visualization, .35f, Modifier.fillMaxWidth().height(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.neo_notifications), color = Color(0xFFF5F0EB), fontSize = 14.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.neo_transcription_status), color = Color(0xFFF5F0EB), modifier = Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = status, onCheckedChange = { value -> scope.launch { settings.updateShowTranscriptionStatus(value) }; if (value) askNotificationPermission() })
        }
        FeatureHelp(R.string.neo_status_hint)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.neo_unread_notification), color = Color(0xFFF5F0EB), modifier = Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = unread, onCheckedChange = { value -> scope.launch { settings.updateNotifyUnreadNotes(value) }; if (value) askNotificationPermission() })
        }
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) {
            Text(stringResource(R.string.neo_android_notifications))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.neo_retention_title), color = Color(0xFFF5F0EB), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30).forEach { value -> FilterChip(selected = days == value,
                onClick = { days = value; scope.launch(kotlinx.coroutines.Dispatchers.IO) { notes.retentionDays = value } },
                label = { Text(stringResource(R.string.neo_days, value)) }) }
        }
    }
}

@Composable fun PhoneCaptureVisualizer(style: String, amplitude: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val ink = Color(0xFFFF8B00)
        val level = sqrt(amplitude.coerceIn(0f, 1f))
        when (style) {
            "ORBIT" -> {
                val radius = size.minDimension * (.18f + level * .22f)
                drawCircle(ink.copy(alpha = .1f + level * .2f), radius)
                drawCircle(ink, radius, style = Stroke(2.dp.toPx()))
                drawCircle(ink, 4.dp.toPx())
            }
            "WAVE" -> {
                val path = Path()
                for (i in 0..80) {
                    val x = size.width * i / 80
                    val y = size.height / 2 + sin(i / 80.0 * PI * 6).toFloat() * level * size.height * .4f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, ink, style = Stroke(2.dp.toPx()))
            }
            else -> {
                val count = 31
                val step = size.width / count
                for (i in 0 until count) {
                    val envelope = .3f + .7f * abs(sin(i * .7f))
                    val height = (level * envelope * size.height * .85f).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(ink, Offset(i * step, (size.height - height) / 2), Size(step * .45f, height),
                        androidx.compose.ui.geometry.CornerRadius(step * .2f))
                }
            }
        }
    }
}
