package com.pimax.whisperflow

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val NoteAmber = Color(0xFFFF8B00)

@Composable internal fun CaptureStatus(capture: InboxRecording) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val label = when (capture.state) {
        "DONE" -> return
        "PROCESSING" -> R.string.neo_note_processing
        "FAILED" -> R.string.neo_failed
        else -> R.string.neo_pending
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = capture.state == "FAILED") {
        scope.launch {
            withContext(Dispatchers.IO) { AudioInboxRepository.get(context).update(capture.id, "PENDING", capture.transcription) }
            WatchTranscriptionWorker.enqueue(context, capture.id)
        }
    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (capture.state == "FAILED") Icons.Default.Refresh else Icons.Default.GraphicEq, null,
            tint = NoteAmber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(stringResource(label), color = NoteAmber, fontSize = 12.sp)
            if (capture.state == "FAILED" && !capture.error.isNullOrBlank()) Text(capture.error, color = Color(0xFFAAA4AF), fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun NotesScreen(
    onOpenRecordings: () -> Unit = {},
    syncOnOpen: Boolean = true,
    initialNoteId: String? = null,
    requestGeneration: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { MarkdownNotes.get(context) }
    val notes by repository.notes.collectAsState(initial = emptyList())
    val trash by repository.trash.collectAsState(initial = emptyList())
    val captures by remember { AudioInboxRepository.get(context).recordings }.collectAsState(initial = emptyList())
    val vault by repository.vault.collectAsState(initial = repository.vaultUri)
    val syncStatus by repository.status.collectAsState(initial = repository.syncMessage)
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var actionIds by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var storageDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                withContext(Dispatchers.IO) { repository.vaultUri = uri.toString() }
                NoteVaultSyncWorker.enqueue(context.applicationContext)
            } catch (_: Exception) { error = context.getString(R.string.notes_folder_error) }
        }
    }
    LaunchedEffect(initialNoteId, requestGeneration) { if (initialNoteId != null) { showTrash = false; openId = initialNoteId } }
    LaunchedEffect(Unit) { if (syncOnOpen) { NoteTrashCleanupWorker.schedule(context.applicationContext); NoteVaultSyncWorker.enqueue(context.applicationContext) } }
    openId?.let { VisualNoteEditor(it, synchronizationEnabled = syncOnOpen, onClose = { openId = null }); return }
    val visibleNotes = if (showTrash) trash else notes
    val visibleIds = visibleNotes.map { it.id }.toSet()
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.intersect(visibleIds) }
    BackHandler(enabled = showTrash || selectedIds.isNotEmpty()) { if (selectedIds.isNotEmpty()) selectedIds = emptySet() else showTrash = false }
    val location = remember(vault) { vault?.let { runCatching {
        android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(it)).replace("primary:", "")
    }.getOrDefault(it) } ?: context.getString(R.string.notes_internal_storage) }
    fun toggle(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun confirm(action: String, ids: List<String>) { actionIds = ids; confirmation = action }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectedIds.isNotEmpty()) {
                NoteIcon(R.string.notes_cancel, Icons.Default.Close) { selectedIds = emptySet() }
                Text(stringResource(R.string.neo_selection, selectedIds.size), modifier = Modifier.weight(1f), color = Color(0xFFF5F0EB), fontSize = 17.sp, maxLines = 1)
                NoteIcon(R.string.neo_select_all, Icons.Default.SelectAll) { selectedIds = if (selectedIds == visibleIds) emptySet() else visibleIds }
                if (showTrash) NoteIcon(R.string.notes_restore, Icons.Default.Restore, enabled = !busy) {
                    val ids = selectedIds.toList(); busy = true
                    scope.launch { try {
                        withContext(Dispatchers.IO) { ids.forEach(repository::restore) }
                        selectedIds = emptySet()
                        if (syncOnOpen) NoteVaultSyncWorker.enqueue(context.applicationContext)
                    } finally { busy = false } }
                }
                NoteIcon(if (showTrash) R.string.neo_delete_forever else R.string.notes_delete, Icons.Default.DeleteOutline, enabled = !busy) {
                    confirm(if (showTrash) "PURGE" else "TRASH", selectedIds.toList())
                }
            } else {
                if (showTrash) NoteIcon(R.string.notes_back, Icons.Default.ArrowBack) { showTrash = false }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (showTrash) R.string.notes_trash else R.string.notes_title),
                        color = Color(0xFFF5F0EB), fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(stringResource(R.string.notes_count, visibleNotes.size), color = Color(0xFFAAA4AF), fontSize = 12.sp)
                }
                if (showTrash) {
                    NoteIcon(R.string.neo_select_all, Icons.Default.SelectAll, enabled = visibleNotes.isNotEmpty()) { selectedIds = visibleIds }
                    NoteIcon(R.string.neo_empty_trash, Icons.Default.DeleteSweep, enabled = visibleNotes.isNotEmpty()) { confirm("PURGE", visibleNotes.map { it.id }) }
                } else {
                    NoteIcon(R.string.notes_storage, Icons.Default.FolderOpen) {
                        if (vault == null) storageDialog = true else picker.launch(android.net.Uri.parse(vault))
                    }
                    NoteIcon(R.string.notes_sync, Icons.Default.Sync) {
                        NoteVaultSyncWorker.enqueue(context.applicationContext)
                        android.widget.Toast.makeText(context, R.string.notes_sync, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    NoteIcon(R.string.notes_trash, Icons.Default.DeleteOutline) { showTrash = true; selectedIds = emptySet() }
                    NoteIcon(R.string.notes_new, Icons.Default.Add, active = true) { scope.launch {
                        openId = withContext(Dispatchers.IO) { repository.create(text = "").id }
                    } }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = NoteAmber)
        error?.let { Text(it, color = Color(0xFFFF7777), fontSize = 12.sp) }
        if (syncStatus == context.getString(R.string.notes_sync_failed)) Text(syncStatus, color = Color(0xFFFF7777), fontSize = 12.sp)
        if (visibleNotes.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Description, null, tint = Color(0xFF7A7580), modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(if (showTrash) R.string.notes_trash_empty else R.string.notes_empty), color = Color(0xFFAAA4AF), fontSize = 14.sp)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(visibleNotes, key = { it.id }) { note ->
                Row(Modifier.fillMaxWidth().semantics { selected = note.id in selectedIds }
                    .background(if (note.id in selectedIds) Color(0xFF29222A) else Color.Transparent)
                    .combinedClickable(onLongClick = { toggle(note.id) }, onClick = {
                        if (selectedIds.isNotEmpty() || showTrash) toggle(note.id) else openId = note.id
                    }).padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
                    if (selectedIds.isNotEmpty()) Checkbox(checked = note.id in selectedIds, onCheckedChange = { toggle(note.id) })
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!showTrash && note.seenAt == 0L) {
                                Box(Modifier.size(6.dp).background(NoteAmber, androidx.compose.foundation.shape.CircleShape))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(note.title, color = Color(0xFFF5F0EB), fontSize = 17.sp,
                                fontWeight = if (note.seenAt == 0L) FontWeight.Bold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (!showTrash) captures.firstOrNull { it.id == note.recordingId }?.let { CaptureStatus(it) }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(note.createdAt)),
                                color = Color(0xFFAAA4AF), fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (note.audioPath != null) Icon(Icons.Default.Mic, null, tint = NoteAmber, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF282832))
            }
        }
    }
    if (storageDialog) AlertDialog(onDismissRequest = { storageDialog = false }, title = { Text(stringResource(R.string.notes_storage)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(location, fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (vault == null) R.string.notes_internal_explanation else R.string.notes_vault_hint))
        } }, confirmButton = { TextButton(onClick = { storageDialog = false; picker.launch(vault?.let(android.net.Uri::parse)) }) { Text(stringResource(R.string.notes_choose_folder)) } },
        dismissButton = { TextButton(onClick = { storageDialog = false }) { Text(stringResource(R.string.notes_close)) } })
    if (confirmation != null) AlertDialog(onDismissRequest = { if (!busy) confirmation = null },
        title = { Text(stringResource(if (confirmation == "PURGE") R.string.neo_delete_forever else R.string.notes_delete)) },
        text = { Text(stringResource(if (confirmation == "PURGE") R.string.neo_confirm_purge else R.string.neo_confirm_delete)) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val action = confirmation; val ids = actionIds; busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { ids.forEach {
                        if (action == "PURGE") repository.purge(it) else repository.delete(it)
                        NoteNotifications.cancel(context, it)
                    } }
                    selectedIds = emptySet(); confirmation = null
                    if (syncOnOpen) NoteVaultSyncWorker.enqueue(context.applicationContext)
                } catch (_: Exception) { error = context.getString(R.string.notes_save_error); confirmation = null }
                finally { busy = false }
            }
        }) { Text(stringResource(if (confirmation == "PURGE") R.string.neo_delete_forever else R.string.notes_delete)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmation = null }) { Text(stringResource(R.string.notes_cancel)) } })
}
