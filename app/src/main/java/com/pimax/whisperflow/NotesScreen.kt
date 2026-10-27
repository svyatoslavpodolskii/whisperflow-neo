package com.pimax.whisperflow

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

private val NoteAmber = Color(0xFFFF8B00)

@Composable fun NotesScreen(onOpenRecordings: () -> Unit, syncOnOpen: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { MarkdownNotes.get(context) }
    val notes by repository.notes.collectAsState(initial = emptyList())
    val trash by repository.trash.collectAsState(initial = emptyList())
    val vault by repository.vault.collectAsState(initial = repository.vaultUri)
    val syncStatus by repository.status.collectAsState(initial = repository.syncMessage)
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var openForEditing by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
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
    LaunchedEffect(Unit) { if (syncOnOpen) { NoteTrashCleanupWorker.schedule(context.applicationContext); NoteVaultSyncWorker.enqueue(context.applicationContext) } }
    openId?.let { MarkdownEditor(it, startEditing = openForEditing, onClose = { openId = null }); return }
    val visibleNotes = if (showTrash) trash else notes
    var retentionDays by remember { mutableIntStateOf(repository.retentionDays) }
    val location = remember(vault) {
        vault?.let { runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(it)).replace("primary:", "")
        }.getOrDefault(it) } ?: context.getString(R.string.notes_internal_storage)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (showTrash) R.string.notes_trash else R.string.notes_title), color = Color(0xFFF5F0EB),
                    fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.notes_count, visibleNotes.size), color = Color(0xFFAAA4AF), fontSize = 12.sp)
            }
            if (!showTrash) IconButton(onClick = { scope.launch {
                openForEditing = true
                openId = withContext(Dispatchers.IO) { repository.create().id }
            } }) { Icon(Icons.Default.Add, stringResource(R.string.notes_new), tint = NoteAmber) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.notes_actions), tint = Color(0xFFC0BAC5)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.notes_storage)) }, onClick = { menu = false; storageDialog = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.notes_sync)) }, onClick = { menu = false; NoteVaultSyncWorker.enqueue(context.applicationContext) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.notes_audio_history)) }, onClick = { menu = false; onOpenRecordings() })
                    DropdownMenuItem(text = { Text(stringResource(if (showTrash) R.string.notes_title else R.string.notes_trash)) }, onClick = { menu = false; showTrash = !showTrash })
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF1C1C26), RoundedCornerShape(12.dp))
            .clickable { storageDialog = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = NoteAmber, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(location, color = Color(0xFFF5F0EB), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (vault == null) R.string.notes_internal_short else R.string.notes_shared_short),
                    color = Color(0xFFAAA4AF), fontSize = 11.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFAAA4AF))
        }
        error?.let { Text(it, color = Color(0xFFFF7777), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        if (syncStatus.isNotBlank()) Text(syncStatus, color = Color(0xFFAAA4AF), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        if (showTrash) TextButton(onClick = {
            retentionDays = if (retentionDays == 30) 7 else 30
            repository.retentionDays = retentionDays
            NoteVaultSyncWorker.enqueue(context.applicationContext)
        }) { Text(stringResource(R.string.notes_retention, retentionDays), color = NoteAmber) }
        Spacer(Modifier.height(16.dp))
        if (visibleNotes.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Description, null, tint = Color(0xFF7A7580), modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(if (showTrash) R.string.notes_trash_empty else R.string.notes_empty), color = Color(0xFFAAA4AF), fontSize = 14.sp)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(visibleNotes, key = { it.id }) { note ->
                Column(Modifier.fillMaxWidth().clickable(enabled = !showTrash) { openForEditing = false; openId = note.id }.padding(vertical = 18.dp)) {
                    Text(note.title, color = Color(0xFFF5F0EB), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(note.createdAt)),
                            color = Color(0xFFAAA4AF), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        if (note.audioPath != null) Icon(Icons.Default.Mic, null, tint = NoteAmber, modifier = Modifier.size(16.dp))
                    }
                    if (showTrash) Row {
                        TextButton(onClick = { scope.launch {
                            withContext(Dispatchers.IO) { repository.restore(note.id) }
                            NoteVaultSyncWorker.enqueue(context.applicationContext)
                        } }) { Text(stringResource(R.string.notes_restore), color = NoteAmber) }

                    }
                }
                HorizontalDivider(color = Color(0xFF282832))
            }
        }
    }
    if (storageDialog) AlertDialog(onDismissRequest = { storageDialog = false },
        title = { Text(stringResource(R.string.notes_storage)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(location, fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (vault == null) R.string.notes_internal_explanation else R.string.notes_vault_hint))
        } },
        confirmButton = { TextButton(onClick = { storageDialog = false; picker.launch(vault?.let(android.net.Uri::parse)) }) { Text(stringResource(R.string.notes_choose_folder)) } },
        dismissButton = { TextButton(onClick = { storageDialog = false }) { Text(stringResource(R.string.notes_close)) } })
}

@Composable private fun MarkdownEditor(id: String, startEditing: Boolean = false, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { MarkdownNotes.get(context) }
    var text by remember(id) { mutableStateOf("") }
    var original by remember(id) { mutableStateOf("") }
    var sourceDocument by remember(id) { mutableStateOf("") }
    var baseHash by remember(id) { mutableStateOf("") }
    var ready by remember(id) { mutableStateOf(false) }
    var preview by rememberSaveable(id) { mutableStateOf(!startEditing) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var audioPath by remember(id) { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    val player = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    DisposableEffect(id) { onDispose { player.value?.release() } }
    val playAudio: () -> Unit = {
        val path = audioPath
        if (path != null) {
            if (playing) { player.value?.pause(); playing = false }
            else {
                player.value?.release()
                runCatching { player.value = android.media.MediaPlayer().apply {
                    setDataSource(path)
                    setOnPreparedListener { it.start(); playing = true }
                    setOnCompletionListener { playing = false }
                    setOnErrorListener { _, _, _ -> playing = false; error = context.getString(R.string.notes_audio_error); true }
                    prepareAsync()
                } }.onFailure { error = context.getString(R.string.notes_audio_error) }
            }
        }
    }
    LaunchedEffect(id) {
        try {
            val content = withContext(Dispatchers.IO) { Triple(repository.read(id), repository.hash(id), repository.draft(id)) }
            sourceDocument = content.first
            original = MarkdownNotes.body(content.first)
            text = MarkdownNotes.body(content.third?.first ?: content.first)
            baseHash = content.third?.second ?: content.second
            audioPath = withContext(Dispatchers.IO) { repository.find(id)?.audioPath?.takeIf { java.io.File(it).exists() } }
            ready = true
        } catch (_: Exception) { error = context.getString(R.string.notes_load_error) }
    }
    val currentText by rememberUpdatedState(text)
    val currentHash by rememberUpdatedState(baseHash)
    val currentOriginal by rememberUpdatedState(original)
    val alreadySaved by rememberUpdatedState(saved)
    DisposableEffect(id) {
        onDispose {
            if (!alreadySaved && currentHash.isNotBlank() && currentText != currentOriginal) repository.queueDraft(id, MarkdownNotes.replaceBody(sourceDocument, currentText, id), currentHash)
        }
    }
    LaunchedEffect(text, ready) {
        if (ready && text != original) { delay(700); repository.queueDraft(id, MarkdownNotes.replaceBody(sourceDocument, text, id), baseHash) }
    }
    val save: () -> Unit = {
        if (ready && !saving) {
            saving = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { val result = repository.saveEdit(id, MarkdownNotes.replaceBody(sourceDocument, text, id), baseHash); repository.clearDraft(id); result }
                    if (result.id != id) android.widget.Toast.makeText(context, context.getString(R.string.notes_conflict_saved), android.widget.Toast.LENGTH_LONG).show()
                    saved = true
                    NoteVaultSyncWorker.enqueue(context.applicationContext)
                    onClose()
                } catch (_: Exception) { error = context.getString(R.string.notes_save_error); saving = false }
            }
        }
    }
    BackHandler { if (ready && text != original) save() else onClose() }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (ready && text != original) save() else onClose() }) { Text(stringResource(R.string.notes_back), color = NoteAmber) }
            TextButton(onClick = { preview = !preview }) { Text(stringResource(if (preview) R.string.notes_edit else R.string.notes_preview), color = NoteAmber) }
            TextButton(onClick = save, enabled = ready && !saving) { Text(stringResource(R.string.notes_save), color = NoteAmber) }
        }
        error?.let { Text(it, color = Color(0xFFFF7777), fontSize = 12.sp) }
        if (!ready) CircularProgressIndicator(color = NoteAmber)
        else if (preview) MarkdownPreview(text, Modifier.weight(1f).fillMaxWidth(), playAudio)
        else OutlinedTextField(value = text, onValueChange = { if (it.length <= 2 * 1024 * 1024) text = it },
            modifier = Modifier.weight(1f).fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Default, fontSize = 17.sp, lineHeight = 26.sp),
            label = { Text(stringResource(R.string.notes_markdown)) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (audioPath != null) TextButton(onClick = playAudio) { Text(stringResource(if (playing) R.string.notes_pause else R.string.notes_play), color = NoteAmber) }
            TextButton(onClick = { confirmDelete = true }, enabled = ready) { Text(stringResource(R.string.notes_delete), color = Color(0xFFFF7777)) }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.notes_delete)) },
        text = { Text(stringResource(R.string.notes_delete_together, repository.retentionDays)) },
        confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) {
            repository.delete(id); repository.clearDraft(id)
            withContext(Dispatchers.Main) { saved = true; onClose() }
            NoteVaultSyncWorker.enqueue(context.applicationContext)
        } }) { Text(stringResource(R.string.notes_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.notes_cancel)) } })
}

@Composable private fun MarkdownPreview(markdown: String, modifier: Modifier, onAudioLink: () -> Unit) {
    val context = LocalContext.current
    var html by remember { mutableStateOf("") }
    LaunchedEffect(markdown) {
        html = withContext(Dispatchers.Default) {
            val body = if (markdown.startsWith("---\n")) markdown.substringAfter("\n---\n", markdown) else markdown
            val rendered = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build().render(Parser.builder().build().parse(body))
            """<html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
            body{background:#0d0d12;color:#f5f0eb;font:17px sans-serif;padding:0;overflow-wrap:anywhere;line-height:1.6}
            a{color:#ffad42}pre{white-space:pre-wrap;background:#1c1c28;padding:12px}blockquote{border-left:3px solid #ff8b00;margin:0;padding-left:12px}
            </style></head><body>$rendered</body></html>"""
        }
    }
    val web = remember { WebView(context).apply {
        setBackgroundColor(AndroidColor.rgb(13, 13, 18))
        settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.blockNetworkLoads = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                if (request.url.toString().endsWith(".wav")) onAudioLink()
                else if (request.url.scheme in listOf("http", "https")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
    } }
    DisposableEffect(web) { onDispose { web.destroy() } }
    AndroidView(factory = { web }, modifier = modifier, update = { view ->
        if (view.tag != html) { view.tag = html; view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) }
    })
}
