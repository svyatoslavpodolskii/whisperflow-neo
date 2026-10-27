package com.pimax.whisperflow

import android.content.Intent
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

@Composable fun NotesScreen(onOpenRecordings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { MarkdownNotes.get(context) }
    val notes by repository.notes.collectAsState(initial = emptyList())
    val trash by repository.trash.collectAsState(initial = emptyList())
    var showTrash by rememberSaveable { mutableStateOf(false) }
    val vault by repository.vault.collectAsState(initial = repository.vaultUri)
    val syncStatus by repository.status.collectAsState(initial = repository.syncMessage)
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
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
    LaunchedEffect(Unit) { NoteVaultSyncWorker.enqueue(context.applicationContext) }
    val id = openId
    if (id != null) {
        MarkdownEditor(id, onClose = { openId = null })
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.notes_title), color = Color(0xFFF5F0EB), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { scope.launch { openId = withContext(Dispatchers.IO) { repository.create().id } } }) { Text(stringResource(R.string.notes_new), color = NoteAmber) }
            TextButton(onClick = { picker.launch(repository.vaultUri?.let(android.net.Uri::parse)) }) { Text(stringResource(R.string.notes_vault), color = NoteAmber) }
            TextButton(onClick = { NoteVaultSyncWorker.enqueue(context.applicationContext) }) { Text(stringResource(R.string.notes_sync), color = NoteAmber) }
        }
        TextButton(onClick = onOpenRecordings) { Text(stringResource(R.string.notes_audio_history), color = Color(0xFFC0BAC5)) }
        TextButton(onClick = { showTrash = !showTrash }) { Text(stringResource(if (showTrash) R.string.notes_title else R.string.notes_trash), color = NoteAmber) }
        Text(if (vault == null) stringResource(R.string.notes_private_hint) else stringResource(R.string.notes_vault_hint), color = Color(0xFFAAA4AF), fontSize = 12.sp)
        if (syncStatus.isNotBlank()) Text(syncStatus, color = NoteAmber, fontSize = 11.sp)
        error?.let { Text(it, color = Color(0xFFFF7777), fontSize = 12.sp) }
        Spacer(Modifier.height(12.dp))
        if (notes.isEmpty()) Text(stringResource(R.string.notes_empty), color = Color(0xFFC0BAC5), modifier = Modifier.padding(vertical = 24.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(if (showTrash) trash else notes, key = { it.id }) { note ->
                Card(Modifier.fillMaxWidth().clickable { if (!showTrash) openId = note.id }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C28))) {
                    Column(Modifier.padding(16.dp)) {
                        Text(note.title, color = Color(0xFFF5F0EB), fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        Text(note.fileName, color = Color(0xFFAAA4AF), fontSize = 11.sp, maxLines = 1)
                        if (showTrash) TextButton(onClick = { scope.launch {
                            withContext(Dispatchers.IO) { repository.restore(note.id) }
                            NoteVaultSyncWorker.enqueue(context.applicationContext)
                        } }) { Text(stringResource(R.string.notes_restore), color = NoteAmber) }
                    }
                }
            }
        }
    }
}

@Composable private fun MarkdownEditor(id: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { MarkdownNotes.get(context) }
    var text by remember(id) { mutableStateOf("") }
    var original by remember(id) { mutableStateOf("") }
    var baseHash by remember(id) { mutableStateOf("") }
    var ready by remember(id) { mutableStateOf(false) }
    var preview by rememberSaveable(id) { mutableStateOf(false) }
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
            original = content.first
            text = content.third?.first ?: content.first
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
            if (!alreadySaved && currentHash.isNotBlank() && currentText != currentOriginal) repository.queueDraft(id, currentText, currentHash)
        }
    }
    LaunchedEffect(text, ready) {
        if (ready && text != original) { delay(700); repository.queueDraft(id, text, baseHash) }
    }
    val save: () -> Unit = {
        if (ready && !saving) {
            saving = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { val result = repository.saveEdit(id, text, baseHash); repository.clearDraft(id); result }
                    if (result.id != id) android.widget.Toast.makeText(context, context.getString(R.string.notes_conflict_saved), android.widget.Toast.LENGTH_LONG).show()
                    saved = true
                    NoteVaultSyncWorker.enqueue(context.applicationContext)
                    onClose()
                } catch (_: Exception) { error = context.getString(R.string.notes_save_error); saving = false }
            }
        }
    }
    BackHandler { if (ready && text != original) save() else onClose() }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (ready && text != original) save() else onClose() }) { Text(stringResource(R.string.notes_back), color = NoteAmber) }
            TextButton(onClick = { preview = !preview }) { Text(stringResource(if (preview) R.string.notes_edit else R.string.notes_preview), color = NoteAmber) }
            TextButton(onClick = save, enabled = ready && !saving) { Text(stringResource(R.string.notes_save), color = NoteAmber) }
        }
        error?.let { Text(it, color = Color(0xFFFF7777), fontSize = 12.sp) }
        if (!ready) CircularProgressIndicator(color = NoteAmber)
        else if (preview) MarkdownPreview(text, Modifier.weight(1f).fillMaxWidth(), playAudio)
        else OutlinedTextField(value = text, onValueChange = { if (it.length <= 2 * 1024 * 1024) text = it },
            modifier = Modifier.weight(1f).fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
            label = { Text(stringResource(R.string.notes_markdown)) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (audioPath != null) TextButton(onClick = playAudio) { Text(stringResource(if (playing) R.string.notes_pause else R.string.notes_play), color = NoteAmber) }
            TextButton(onClick = { confirmDelete = true }, enabled = ready) { Text(stringResource(R.string.notes_delete), color = Color(0xFFFF7777)) }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.notes_delete)) },
        text = { Text(stringResource(R.string.notes_delete_hint)) },
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
            body{background:#0d0d12;color:#f5f0eb;font:16px sans-serif;padding:12px;overflow-wrap:anywhere;line-height:1.6}
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
