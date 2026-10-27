package com.pimax.whisperflow.wear

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "⌚ WearMain"

// ═══════════════════════════════════════════════════════════════════════════════
// 🎨 AMBER/NOIR COLOR TOKENS
// ═══════════════════════════════════════════════════════════════════════════════

private val Amber = Color(0xFFFF8B00)
private val AmberLight = Color(0xFFFFAD42)
private val AmberDim = Color(0xFF7A4500)
private val Copper = Color(0xFFE85D26)
private val DeepNoir = Color(0xFF0A0A0F)
private val DarkSlate = Color(0xFF14141C)
private val CardSurface = Color(0xFF1C1C26)
private val TextWhite = Color(0xFFF5F0EB)
private val TextGray = Color(0xFF7A7580)
private val TextMuted = Color(0xFF4A4654)
private val Emerald = Color(0xFF00C77B)
private val CoralRed = Color(0xFFFF4444)

enum class WatchState {
    IDLE, RECORDING, SENDING, TRANSCRIBING, RESULT, ERROR
}

// ═══════════════════════════════════════════════════════════════════════════════
// 🏠 ACTIVITY
// ═══════════════════════════════════════════════════════════════════════════════

class WearMainActivity : ComponentActivity() {
    private lateinit var recorder: WearAudioRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = WearAudioRecorder(this)

        setContent {
            WearWhisperApp(recorder)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder.cancel()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 📱 MAIN UI
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun WearWhisperApp(
    recorder: WearAudioRecorder
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var watchState by remember { mutableStateOf(WatchState.IDLE) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var permissionStart by remember { mutableStateOf(false) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var resultText by remember { mutableStateOf("") }
    var liveText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var hasMicPermission by remember { mutableStateOf(false) }

    val amplitudeHistory = remember { mutableStateListOf<Float>() }

    DisposableEffect(watchState) {
        val window = (context as? ComponentActivity)?.window
        if (watchState == WatchState.RECORDING) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        permissionStart = granted
        if (!granted) {
            errorText = context.getString(R.string.error_mic_permission)
            watchState = WatchState.ERROR
        }
    }

    LaunchedEffect(Unit) {
        hasMicPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(watchState) {
        if (watchState == WatchState.RESULT || watchState == WatchState.ERROR) {
            delay(1_500)
            if (watchState == WatchState.RESULT || watchState == WatchState.ERROR) {
                watchState = WatchState.IDLE
                resultText = ""
                errorText = ""
            }
        }
    }

    LaunchedEffect(amplitude) {
        if (watchState == WatchState.RECORDING) {
            amplitudeHistory.add(amplitude)
            if (amplitudeHistory.size > 24) amplitudeHistory.removeAt(0)
        }
    }

    LaunchedEffect(watchState) {
        if (watchState == WatchState.RECORDING) {
            val started = SystemClock.elapsedRealtime()
            while (true) {
                elapsedSeconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
                delay(200)
            }
        }
    }

    val onRecord: () -> Unit = {
        when (watchState) {
            WatchState.IDLE, WatchState.RESULT, WatchState.ERROR -> {
                if (recorder.hasPendingAudio) {
                    watchState = WatchState.SENDING
                    scope.launch {
                        finishRecording(recorder, context) { s, t, e ->
                            watchState = s
                            if (t != null) resultText = t
                            if (e != null) errorText = e
                        }
                    }
                } else if (!hasMicPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    watchState = WatchState.RECORDING
                    elapsedSeconds = 0
                    resultText = ""
                    liveText = ""
                    errorText = ""
                    amplitudeHistory.clear()
                    vibrateStart(context)
                    scope.launch {
                        recorder.startRecording(
                            onAmplitudeUpdate = { amplitude = it },
                            onError = {
                                errorText = context.getString(R.string.error_recording)
                                watchState = WatchState.ERROR
                                vibrateError(context)
                            },
                            onAutoStop = {
                                scope.launch {
                                    finishRecording(recorder, context) { s, t, e ->
                                        watchState = s
                                        if (t != null) resultText = t
                                        if (e != null) errorText = e
                                    }
                                }
                            },
                            onMaxDuration = {
                                scope.launch {
                                    finishRecording(recorder, context) { s, t, e ->
                                        watchState = s
                                        if (t != null) resultText = t
                                        if (e != null) errorText = e
                                    }
                                }
                            }
                        )
                    }
                }
            }
            WatchState.RECORDING -> {
                watchState = WatchState.SENDING
                vibrateStop(context)
                scope.launch {
                    finishRecording(recorder, context) { s, t, e ->
                        watchState = s
                        if (t != null) resultText = t
                        if (e != null) errorText = e
                    }
                }
            }
            else -> {}
        }
    }

    LaunchedEffect(permissionStart) {
        if (permissionStart) {
            permissionStart = false
            onRecord()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI
    // ═══════════════════════════════════════════════════════════════════════════

    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DeepNoir),
                contentAlignment = Alignment.Center
            ) {
                // Subtle gradient overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(DarkSlate.copy(alpha = 0.6f), Color.Transparent),
                                radius = 400f
                            )
                        )
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)
                ) {

                    RecordButton(
                        watchState = watchState,
                        amplitude = amplitude,
                        onClick = onRecord
                    )

                    Spacer(Modifier.height(10.dp))

                    // Status content
                    when (watchState) {
                        WatchState.RECORDING -> {
                            WaveformVisualizer(
                                amplitudes = amplitudeHistory,
                                modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            // Show live transcription if available
                            if (liveText.isNotEmpty()) {
                                LiveTextPill(text = liveText)
                            } else {
                                Text(stringResource(R.string.recording_timer, elapsedSeconds / 60, elapsedSeconds % 60), color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        WatchState.SENDING -> {
                            PulsingDots()
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.saving), color = TextGray, fontSize = 12.sp)
                        }
                        WatchState.TRANSCRIBING -> {
                            PulsingDots()
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.transcribing), color = Amber.copy(alpha = 0.8f), fontSize = 12.sp)
                        }
                        WatchState.RESULT -> {
                            Text(resultText, color = Emerald, fontSize = 12.sp)
                        }
                        WatchState.ERROR -> {
                            ErrorCard(text = errorText)
                        }
                        WatchState.IDLE -> {
                            Text(stringResource(R.string.tap_to_record), color = TextMuted, fontSize = 12.sp)
                        }
                    }


                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 🎛️ COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun RecordButton(watchState: WatchState, amplitude: Float, onClick: () -> Unit) {
    val isRecording = watchState == WatchState.RECORDING
    val isBusy = watchState == WatchState.SENDING || watchState == WatchState.TRANSCRIBING

    val pulse = if (isRecording) {
        val transition = rememberInfiniteTransition(label = "recordingPulse")
        val value by transition.animateFloat(
            initialValue = 1f, targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse"
        )
        value
    } else 1f
    val scale = pulse
    val ringAlpha = if (isRecording) 0.4f else 0f

    val buttonColor = when {
        isRecording -> Amber
        isBusy -> AmberDim.copy(alpha = 0.5f)
        else -> AmberDim
    }

    Box(contentAlignment = Alignment.Center) {
        // Outer ring when recording
        if (isRecording) {
            Canvas(Modifier.size(80.dp)) {
                drawCircle(
                    color = Amber.copy(alpha = ringAlpha * 0.4f),
                    radius = size.minDimension / 2f,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        Button(
            onClick = onClick,
            modifier = Modifier.size((64 * scale).dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = buttonColor, contentColor = DeepNoir),
            shape = CircleShape,
            enabled = !isBusy
        ) {
            Icon(
                imageVector = when {
                    isRecording -> Icons.Default.Stop
                    watchState == WatchState.ERROR -> Icons.Default.Refresh
                    else -> Icons.Default.Mic
                },
                contentDescription = stringResource(when {
                    isRecording -> R.string.stop
                    watchState == WatchState.ERROR -> R.string.retry
                    else -> R.string.tap_to_record
                }),
                modifier = Modifier.size(26.dp),
                tint = if (isRecording) DeepNoir else TextWhite
            )
        }
    }
}

@Composable
private fun WaveformVisualizer(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    val barCount = 24
    val infiniteTransition = rememberInfiniteTransition(label = "idle")
    val idlePhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 6.28f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    // Center-out display: newest samples in center, older on edges
    val halfBars = barCount / 2
    val recentAmps = if (amplitudes.size >= halfBars) amplitudes.takeLast(halfBars) else amplitudes
    // Lower boost for better sensitivity - raw amplitude is 0-1, we want subtle movements
    val boosted = recentAmps.map { (it * 2.5f).coerceIn(0f, 1f) }
    // Require higher threshold to detect real audio vs silence
    val hasAudio = boosted.isNotEmpty() && boosted.any { it > 0.15f }

    Canvas(modifier) {
        val totalWidth = size.width * 0.9f
        val startX = (size.width - totalWidth) / 2f
        val barWidth = totalWidth / (barCount * 1.6f)
        val gap = barWidth * 0.6f
        val centerY = size.height / 2f
        val centerX = size.width / 2f

        for (i in 0 until barCount) {
            // Map bar index to amplitude: center bars = newest, edge bars = older
            val distFromCenter = kotlin.math.abs(i - halfBars)

            val amp = if (hasAudio && boosted.isNotEmpty()) {
                val ampIndex = (halfBars - 1 - distFromCenter).coerceIn(0, boosted.size - 1)
                // Don't force minimum - let quiet be quiet
                boosted[ampIndex]
            } else {
                // Idle wave animation - gentle breathing
                val phase = idlePhase + i * 0.28f
                (kotlin.math.sin(phase) * 0.06f + 0.08f).toFloat()
            }

            // Scale bar height - minimum 3dp, max 85% of height
            val barHeight = (amp * size.height * 0.85f).coerceAtLeast(3f)
            val x = startX + i * (barWidth + gap)

            val color = if (!hasAudio) AmberDim.copy(alpha = 0.2f)
            else when {
                amp > 0.7f -> AmberLight
                amp > 0.5f -> Amber
                amp > 0.3f -> AmberDim.copy(alpha = 0.9f)
                amp > 0.15f -> AmberDim.copy(alpha = 0.6f)
                else -> AmberDim.copy(alpha = 0.3f)
            }

            // Glow for high amplitude only
            if (hasAudio && amp > 0.5f) {
                drawRoundRect(
                    color = Amber.copy(alpha = amp * 0.25f),
                    topLeft = Offset(x - 1f, centerY - barHeight / 2f - 2f),
                    size = Size(barWidth + 2f, barHeight + 4f),
                    cornerRadius = CornerRadius(barWidth, barWidth)
                )
            }

            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
private fun LiveTextPill(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "live")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardSurface.copy(alpha = 0.8f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Amber.copy(alpha = pulse)))
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                color = TextWhite.copy(alpha = 0.95f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

@Composable
private fun PulsingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(450, delayMillis = i * 120), RepeatMode.Reverse),
                label = "dot$i"
            )
            Box(Modifier.size(6.dp).clip(CircleShape).background(Amber.copy(alpha = alpha)))
        }
    }
}

@Composable
private fun ResultCard(text: String) {
    var displayedCount by remember { mutableIntStateOf(0) }
    var prevText by remember { mutableStateOf("") }

    LaunchedEffect(text) {
        if (text.length > prevText.length) {
            for (i in displayedCount.coerceAtMost(text.length) until text.length) {
                displayedCount = i + 1
                delay(20L)
            }
        } else displayedCount = text.length
        prevText = text
    }

    val visible = text.take(displayedCount)
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(0f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "c")

    Box(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(visible, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (displayedCount < text.length) {
                Text("▎", color = Amber.copy(alpha = cursorAlpha), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ErrorCard(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CoralRed.copy(alpha = 0.15f))
            .padding(10.dp)
    ) {
        Text(text, color = CoralRed, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ModeIndicator(useDirectGpu: Boolean, connected: Boolean, onClick: () -> Unit) {
    val dotColor by animateFloatAsState(if (connected) 1f else 0f, tween(300), label = "dot")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(CardSurface.copy(alpha = 0.7f)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Icon(
            if (useDirectGpu) Icons.Default.Wifi else Icons.Default.PhoneAndroid,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = if (useDirectGpu) Amber else TextGray
        )
        Spacer(Modifier.width(5.dp))
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (dotColor > 0.5f) Emerald else CoralRed))
        Spacer(Modifier.width(5.dp))
        Text(if (useDirectGpu) "Direct" else "Relay", color = if (useDirectGpu) Amber else TextGray, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 🔧 HELPERS
// ═══════════════════════════════════════════════════════════════════════════════

private suspend fun finishRecording(
    recorder: WearAudioRecorder,
    context: Context,
    onStateChange: (WatchState, String?, String?) -> Unit
) {
    onStateChange(WatchState.SENDING, null, null)
    try {
        val wavBytes = recorder.stopAndGetWav()
        if (wavBytes == null) {
            onStateChange(WatchState.ERROR, null, context.getString(R.string.no_speech))
            vibrateError(context)
            return
        }
        // Phase 1: persist first. Legacy transcription is not a durable delivery ACK.
        withContext(Dispatchers.IO) { PendingRecordingStore(context).save(wavBytes) }
        recorder.confirmSaved()
        onStateChange(WatchState.RESULT, context.getString(R.string.saved), null)
        vibrateSuccess(context)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e(TAG, "Unable to finalize recording", error)
        onStateChange(WatchState.ERROR, null, context.getString(R.string.error_save))
        vibrateError(context)
    }
}

private fun getVibrator(context: Context): Vibrator {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
}

private fun vibrateStart(context: Context) = getVibrator(context).vibrate(VibrationEffect.createOneShot(25, 70))
private fun vibrateStop(context: Context) = getVibrator(context).vibrate(VibrationEffect.createOneShot(20, 50))
private fun vibrateSuccess(context: Context) = getVibrator(context).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 40, 25), intArrayOf(0, 90, 0, 50), -1))
private fun vibrateError(context: Context) = getVibrator(context).vibrate(VibrationEffect.createOneShot(80, 150))
