package com.pimax.whisperflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun WatchInboxSection(rows: List<InboxRecording>, onCopy: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { AudioInboxRepository.get(context) }
    if (rows.isNotEmpty()) {
        Text(stringResource(R.string.memory_watch_title), color = Color(0xFFFF8B00), fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
    }
    for (recording in rows) {
        val status = when (recording.state) {
            "PROCESSING" -> R.string.memory_processing
            "DONE" -> R.string.memory_done
            "FAILED" -> R.string.memory_failed
            else -> R.string.memory_pending
        }
        HistoryCard(
            HistoryEntry(recording.transcription, recording.createdAt, recording.durationMs, null, recording.audioPath, "WATCH"),
            onCopy = { if (recording.transcription.isNotBlank()) onCopy(recording.transcription) },
            onDelete = { scope.launch(Dispatchers.IO) { repository.delete(recording.id) } }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(status), color = Color(0xFFFFAD42), fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            if (recording.state == "FAILED" || recording.state == "PENDING") {
                SmallActionButton(stringResource(R.string.memory_retry), Color(0xFFFF8B00)) {
                    scope.launch(Dispatchers.IO) {
                        repository.update(recording.id, "PENDING")
                        WatchTranscriptionWorker.enqueue(context.applicationContext, recording.id)
                    }
                }
            }
        }
        recording.error?.let { Text(it, color = Color(0xFFFF7777), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
        Spacer(Modifier.height(16.dp))
    }
}
