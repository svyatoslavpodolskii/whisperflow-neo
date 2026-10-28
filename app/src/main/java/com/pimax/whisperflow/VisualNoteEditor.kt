package com.pimax.whisperflow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

private val EditorAmber = Color(0xFFFF8B00)

@Composable internal fun NoteIcon(label: Int, icon: ImageVector, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(42.dp)) {
        Icon(icon, stringResource(label), tint = if (!enabled) Color(0xFF55515B) else if (active) EditorAmber else Color(0xFFD8D2DC), modifier = Modifier.size(22.dp))
    }
}

/** Keep plain Obsidian links and task markers intact when serializing the visual editor. */
internal fun portableMarkdown(markdown: String): String = markdown
    .replace("\\[\\[", "[[").replace("\\]\\]", "]]")
    .replace("\\[ \\]", "[ ]").replace("\\[x\\]", "[x]").replace("\\[X\\]", "[X]")
    .replace(Regex("(?m)^(\\s*[-*+] )☐ ")) { it.groupValues[1] + "[ ] " }
    .replace(Regex("(?m)^(\\s*[-*+] )☑ ")) { it.groupValues[1] + "[x] " }

internal fun prepareVisualMarkdown(markdown: String): String =
    markdown.replace(Regex("(?m)^(\\s*[-*+] )\\[([ xX])] ")) {
        it.groupValues[1] + (if (it.groupValues[2] == " ") "☐ " else "☑ ")
    }

/** Advanced Markdown that this editor cannot round-trip stays in source mode rather than losing structure. */
internal fun requiresSourceMode(markdown: String): Boolean =
    Regex("(?m)^\\s*(?:\u0060{3}|~{3}|>|\\$\\$|<[/!A-Za-z])|^\\s*\\|.*\\|\\s*$|!\\[").containsMatchIn(markdown)

internal fun toggleNoteHeading(state: RichTextState) {
    val text = state.annotatedString.text
    val selection = state.selection
    val start = text.lastIndexOf('\n', (selection.min - 1).coerceAtLeast(-1)) + 1
    val end = text.indexOf('\n', selection.max).takeIf { it >= 0 } ?: text.length
    state.selection = TextRange(start, end)
    state.toggleSpanStyle(SpanStyle(fontSize = 1.5.em, fontWeight = FontWeight.Bold))
    state.selection = selection
}

/** SetText is a whole-document accessibility operation, not an incremental rich-text edit. */
internal fun replaceVisualContent(state: RichTextState, text: String) {
    val html = text.split('\n').joinToString("") {
        "<p>" + it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</p>"
    }
    state.setHtml(html)
    state.selection = TextRange(state.annotatedString.length)
}

@Composable internal fun VisualNoteEditor(id: String, synchronizationEnabled: Boolean = true, onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { MarkdownNotes.get(context) }
    val scope = rememberCoroutineScope()
    val rich = rememberRichTextState()
    var text by remember(id) { mutableStateOf("") }
    var original by remember(id) { mutableStateOf("") }
    var document by remember(id) { mutableStateOf("") }
    var baseHash by remember(id) { mutableStateOf("") }
    var baseline by remember(id) { mutableStateOf("") }
    var ready by remember(id) { mutableStateOf(false) }
    var dirty by remember(id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var sourceMode by rememberSaveable(id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var audioPath by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var preparingAudio by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("https://") }
    var undo by remember { mutableStateOf(listOf<String>()) }
    var redo by remember { mutableStateOf(listOf<String>()) }
    var lastUndoTime by remember { mutableLongStateOf(0L) }
    val player = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    val notes by repository.notes.collectAsState(initial = emptyList())
    val liveNote = notes.firstOrNull { it.id == id }
    val captures by remember { AudioInboxRepository.get(context).recordings }.collectAsState(initial = emptyList())
    val capture = captures.firstOrNull { it.id == liveNote?.recordingId }
    fun acceptEdit(value: String) {
        if (value == text || value.length > 2 * 1024 * 1024) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (undo.isEmpty() || now - lastUndoTime > 650) { undo = (undo + text).takeLast(30); lastUndoTime = now }
        redo = emptyList(); text = value; dirty = true
    }
    fun restoreEditor(value: String) {
        rich.setMarkdown(prepareVisualMarkdown(value)); baseline = portableMarkdown(rich.toMarkdown())
        text = value; dirty = true
    }
    LaunchedEffect(id) {
        try {
            val snapshot = withContext(Dispatchers.IO) {
                val pair = repository.snapshot(id)
                Triple(pair.first, pair.second, repository.draft(id))
            }
            document = snapshot.first
            original = MarkdownNotes.body(snapshot.first)
            text = MarkdownNotes.body(snapshot.third?.first ?: snapshot.first)
            baseHash = snapshot.third?.second ?: snapshot.second
            dirty = text != original
            sourceMode = requiresSourceMode(text)
            rich.setMarkdown(prepareVisualMarkdown(text))
            baseline = portableMarkdown(rich.toMarkdown())
            withContext(Dispatchers.IO) { NoteNotifications.read(context, id) }
            audioPath = withContext(Dispatchers.IO) { repository.find(id)?.audioPath?.takeIf { java.io.File(it).exists() } }
            ready = true
        } catch (_: Exception) { error = context.getString(R.string.notes_load_error) }
    }
    // A transcript that arrives while an unedited note is open becomes visible immediately.
    LaunchedEffect(liveNote?.updatedAt) {
        if (ready && !dirty) {
            val snapshot = withContext(Dispatchers.IO) { repository.snapshot(id) }
            if (snapshot.second != baseHash) {
                document = snapshot.first; baseHash = snapshot.second
                original = MarkdownNotes.body(snapshot.first); text = original
                if (requiresSourceMode(text)) sourceMode = true
                rich.setMarkdown(prepareVisualMarkdown(text)); baseline = portableMarkdown(rich.toMarkdown())
                withContext(Dispatchers.IO) { NoteNotifications.read(context, id) }
            }
        }
    }
    LaunchedEffect(ready, sourceMode) {
        if (ready && !sourceMode) snapshotFlow { portableMarkdown(rich.toMarkdown()) }.collectLatest {
            if (it != baseline) { baseline = it; acceptEdit(it) }
        }
    }
    LaunchedEffect(text, ready) {
        if (ready && dirty) { delay(500); repository.queueDraft(id, MarkdownNotes.replaceBody(document, text, id), baseHash) }
    }
    DisposableEffect(id) {
        onDispose {
            player.value?.release()
            // Read the current snapshot cells, including the final keystroke, rather than a stale callback copy.
            val visualBody = portableMarkdown(rich.toMarkdown())
            val latestBody = if (!sourceMode && visualBody != baseline) visualBody else text
            if (!saved && ready && (dirty || latestBody != original))
                repository.queueDraft(id, MarkdownNotes.replaceBody(document, latestBody, id), baseHash)
        }
    }
    val save: () -> Unit = {
        if (ready && !saving) {
            saving = true
            val body = if (!sourceMode && portableMarkdown(rich.toMarkdown()) != baseline) portableMarkdown(rich.toMarkdown()) else text
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val result = if (dirty || body != original) repository.saveEdit(id, MarkdownNotes.replaceBody(document, body, id), baseHash) else repository.find(id)!!
                        repository.clearDraft(id); result
                    }
                    saved = true
                    if (result.id != id) android.widget.Toast.makeText(context, R.string.notes_conflict_saved, android.widget.Toast.LENGTH_LONG).show()
                    if (synchronizationEnabled) NoteVaultSyncWorker.enqueue(context.applicationContext)
                    onClose()
                } catch (_: Exception) { error = context.getString(R.string.notes_save_error); saving = false }
            }
        }
    }
    BackHandler { if (ready) save() else onClose() }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NoteIcon(R.string.notes_back, Icons.Default.ArrowBack) { if (ready) save() else onClose() }
            Text(stringResource(if (sourceMode) R.string.neo_source_editor else R.string.neo_visual_editor),
                color = Color(0xFFF5F0EB), fontSize = 16.sp, modifier = Modifier.weight(1f))
            NoteIcon(if (sourceMode) R.string.neo_visual_editor else R.string.neo_source_editor, if (sourceMode) Icons.Default.Edit else Icons.Default.Code, active = sourceMode,
                enabled = !sourceMode || !requiresSourceMode(text)) {
                if (sourceMode) { rich.setMarkdown(prepareVisualMarkdown(text)); baseline = portableMarkdown(rich.toMarkdown()) }
                sourceMode = !sourceMode
            }
            NoteIcon(R.string.notes_save, Icons.Default.Check, enabled = ready && !saving, active = true, onClick = save)
        }
        error?.let { Text(it, color = Color(0xFFFF7777), fontSize = 12.sp) }
        if (sourceMode && requiresSourceMode(text)) Text(stringResource(R.string.neo_advanced_markdown), color = Color(0xFFAAA4AF), fontSize = 12.sp)
        if (capture != null && capture.state != "DONE") CaptureStatus(capture)
        if (!ready) CircularProgressIndicator(color = EditorAmber)
        else if (sourceMode) OutlinedTextField(
            value = text, onValueChange = { acceptEdit(it) }, enabled = !saving,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = TextStyle(color = Color(0xFFF5F0EB), fontSize = 16.sp, lineHeight = 25.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
        else {
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                BasicRichTextEditor(state = rich, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).semantics {
                        setText { replacement ->
                            if (saving || replacement.length > 2 * 1024 * 1024) false else {
                                replaceVisualContent(rich, replacement.text)
                                val value = portableMarkdown(rich.toMarkdown())
                                baseline = value; acceptEdit(value)
                                true
                            }
                        }
                    },
                    textStyle = TextStyle(color = Color(0xFFF5F0EB), fontSize = 18.sp, lineHeight = 28.sp),
                    cursorBrush = SolidColor(EditorAmber),
                    decorationBox = { field ->
                        if (rich.annotatedString.text.isEmpty()) Text(stringResource(R.string.neo_start_writing), color = Color(0xFF7A7580), fontSize = 18.sp)
                        field()
                    })
            }
        }
        if (!sourceMode && ready) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF1C1C28), RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState())) {
                NoteIcon(R.string.neo_bold, Icons.Default.FormatBold, active = rich.currentSpanStyle.fontWeight == FontWeight.Bold) { rich.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
                NoteIcon(R.string.neo_italic, Icons.Default.FormatItalic, active = rich.currentSpanStyle.fontStyle == FontStyle.Italic) { rich.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
                NoteIcon(R.string.neo_heading, Icons.Default.Title) { toggleNoteHeading(rich) }
                NoteIcon(R.string.neo_list, Icons.Default.FormatListBulleted) { rich.toggleUnorderedList() }
                NoteIcon(R.string.neo_ordered, Icons.Default.FormatListNumbered) { rich.toggleOrderedList() }
                NoteIcon(R.string.neo_strike, Icons.Default.StrikethroughS) { rich.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }
                NoteIcon(R.string.neo_code, Icons.Default.Code) { rich.toggleCodeSpan() }
                NoteIcon(R.string.neo_link, Icons.Default.Link) { linkDialog = true }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NoteIcon(R.string.neo_undo, Icons.Default.Undo, enabled = undo.isNotEmpty()) {
                val previous = undo.last(); undo = undo.dropLast(1); redo = redo + text; restoreEditor(previous)
            }
            NoteIcon(R.string.neo_redo, Icons.Default.Redo, enabled = redo.isNotEmpty()) {
                val next = redo.last(); redo = redo.dropLast(1); undo = undo + text; restoreEditor(next)
            }
            Spacer(Modifier.weight(1f))
            if (audioPath != null) {
                NoteIcon(if (playing) R.string.notes_pause else R.string.notes_play, if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, enabled = !preparingAudio) {
                    if (playing) { player.value?.pause(); playing = false }
                    else if (player.value != null) { player.value?.start(); playing = true }
                    else runCatching {
                        preparingAudio = true
                        player.value = android.media.MediaPlayer().apply {
                            setDataSource(audioPath)
                            setOnPreparedListener { if (player.value === it) { preparingAudio = false; playing = true; it.start() } }
                            setOnCompletionListener { playing = false }
                            setOnErrorListener { _, _, _ -> preparingAudio = false; playing = false; error = context.getString(R.string.notes_audio_error); true }
                            prepareAsync()
                        }
                    }.onFailure { preparingAudio = false; error = context.getString(R.string.notes_audio_error) }
                }
            }
            NoteIcon(R.string.notes_delete, Icons.Default.DeleteOutline, enabled = ready && !saving) { confirmDelete = true }
        }
    }
    if (linkDialog) AlertDialog(onDismissRequest = { linkDialog = false }, title = { Text(stringResource(R.string.neo_link)) },
        text = { OutlinedTextField(linkUrl, { linkUrl = it }, label = { Text("URL") }) },
        confirmButton = { TextButton(onClick = {
            if (validServiceUrl(linkUrl)) {
                if (rich.selection.collapsed) rich.addLink(linkUrl, linkUrl) else rich.addLinkToSelection(linkUrl)
                linkDialog = false
            }
        }) { Text(stringResource(R.string.notes_save)) } },
        dismissButton = { TextButton(onClick = { linkDialog = false }) { Text(stringResource(R.string.notes_cancel)) } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.notes_delete)) },
        text = { Text(stringResource(R.string.notes_delete_together, repository.retentionDays)) },
        confirmButton = { TextButton(onClick = { scope.launch {
            withContext(Dispatchers.IO) { repository.delete(id); repository.clearDraft(id); NoteNotifications.cancel(context, id) }
            saved = true
            if (synchronizationEnabled) NoteVaultSyncWorker.enqueue(context.applicationContext)
            onClose()
        } }) { Text(stringResource(R.string.notes_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.notes_cancel)) } })
}
