package com.learnanywhere.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.learnanywhere.data.Document
import com.learnanywhere.speech.VoiceInput

/**
 * Single-screen SPA with three states:
 *  - EMPTY    → entry hero: add content or just ask by voice
 *  - LIBRARY  → ask + reply + document list with per-row context menus
 *  - PLAYBACK → persistent now-playing bar with read-along preview
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnAnywhereScreen(ctl: UiController) {
    var dialog by remember { mutableStateOf(DialogType.None) }
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var figuresDocId by remember { mutableStateOf<String?>(null) }

    // SAF: pick a PDF
    val pickPdf: ActivityResultLauncher<String> = rememberLauncherForActivityResult(
        contract = object : ActivityResultContract<String, Uri?>() {
            override fun createIntent(context: Context, input: String): Intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = input
                }
            override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
                if (resultCode == Activity.RESULT_OK && intent != null) intent.data else null
        }
    ) { uri -> uri?.let { ctl.addPdf(it) } }

    val docs = ctl.docs.value
    val playback = ctl.playback.value
    val playbackVisible = playback.isPlaying || playback.queue.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Headphones, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Text("LearnAnywhere", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    if (ctl.thread.value.isNotEmpty()) {
                        IconButton(onClick = { ctl.newChat() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "New chat")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        // Bottom chrome hosts the swipe-up sessions handle (entry #2) and,
        // when audio is live, the now-playing bar above it.
        bottomBar = { BottomChrome(ctl, playbackVisible) { showSessions = true } }
    ) { pad ->
        if (docs.isEmpty()) {
            EmptyState(
                ctl,
                modifier = Modifier.padding(pad),
                onAddPdf = { pickPdf.launch("application/pdf") },
                onAddUrl = { dialog = DialogType.Url },
                onAddText = { dialog = DialogType.Text },
                onOpenSessions = { showSessions = true })
        } else {
            LibraryState(
                ctl,
                modifier = Modifier.padding(pad),
                onAddPdf = { pickPdf.launch("application/pdf") },
                onAddUrl = { dialog = DialogType.Url },
                onAddText = { dialog = DialogType.Text },
                onShowFigures = { figuresDocId = it },
                onOpenSessions = { showSessions = true })
        }
    }

    when (dialog) {
        DialogType.Url -> UrlDialog(ctl) { dialog = DialogType.None }
        DialogType.Text -> TextDialog(ctl) { dialog = DialogType.None }
        DialogType.None -> {}
    }
    if (showSettings) SettingsSheet(ctl) { showSettings = false }
    if (showSessions) SessionsSheet(ctl) { showSessions = false }
    figuresDocId?.let { id -> FiguresDialog(ctl, id) { figuresDocId = null } }
}

private enum class DialogType { None, Url, Text }

// =====================================================================
// EMPTY STATE — entry

@Composable
private fun EmptyState(
    ctl: UiController,
    modifier: Modifier,
    onAddPdf: () -> Unit,
    onAddUrl: () -> Unit,
    onAddText: () -> Unit,
    onOpenSessions: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Sessions entry #1: top-left, below the header.
        HistoryEntryButton(onOpenSessions,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 2.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Spacer(Modifier.weight(0.7f))
        Icon(Icons.Outlined.Headphones, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Learn anything, hands-free",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Add a paper or an article. Ask about it by voice while it's read to you.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))

        EntryButton(Icons.Outlined.PictureAsPdf, "Add a PDF", filled = true, onClick = onAddPdf)
        Spacer(Modifier.height(10.dp))
        EntryButton(Icons.Outlined.Link, "Add from URL", filled = false, onClick = onAddUrl)
        Spacer(Modifier.height(10.dp))
        EntryButton(Icons.Outlined.ContentPaste, "Paste text", filled = false, onClick = onAddText)

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text("  or  ", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f))
        }
        Spacer(Modifier.height(22.dp))

        MicButton(ctl, size = 72.dp)
        Spacer(Modifier.height(8.dp))
        Text(voiceHint(ctl.voiceState.value),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ctl.voicePartial.value.isNotBlank() &&
            ctl.voiceState.value != VoiceInput.State.IDLE) {
            Spacer(Modifier.height(6.dp))
            Text(ctl.voicePartial.value, style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center)
        }

        if (ctl.thread.value.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()) {
                ctl.thread.value.forEach { turn -> TurnBubble(turn) }
            }
        }
        ctl.error.value?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HistoryEntryButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onOpen, modifier = modifier) {
        Icon(Icons.Outlined.History, contentDescription = "Sessions")
    }
}

@Composable
private fun EntryButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    filled: Boolean,
    onClick: () -> Unit
) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
    val mod = Modifier.fillMaxWidth().height(54.dp)
    if (filled) Button(onClick = onClick, modifier = mod, content = content)
    else FilledTonalButton(onClick = onClick, modifier = mod, content = content)
}

private fun voiceHint(state: VoiceInput.State): String = when (state) {
    VoiceInput.State.IDLE -> "Just ask by voice"
    VoiceInput.State.LOADING -> "Loading speech model…"
    VoiceInput.State.LISTENING -> "Listening — tap to stop"
    VoiceInput.State.TRANSCRIBING -> "Transcribing…"
}

// =====================================================================
// LIBRARY STATE

@Composable
private fun LibraryState(
    ctl: UiController,
    modifier: Modifier,
    onAddPdf: () -> Unit,
    onAddUrl: () -> Unit,
    onAddText: () -> Unit,
    onShowFigures: (String) -> Unit,
    onOpenSessions: () -> Unit
) {
    val docs = ctl.docs.value
    val playingId = ctl.playback.value.queue.getOrNull(ctl.playback.value.cursor)
        .takeIf { ctl.playback.value.isPlaying }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Sessions entry #1: top-left, below the header.
        item { HistoryEntryButton(onOpenSessions) }
        item { AskCard(ctl) }
        if (ctl.thread.value.isNotEmpty()) {
            items(ctl.thread.value) { turn -> TurnBubble(turn) }
        }
        ctl.error.value?.let {
            item {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp))
            }
        }

        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Library", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    val n = ctl.selected.value.size
                    TextButton(onClick = { ctl.listen() }, enabled = n > 0) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (n > 0) "Play $n" else "Play")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onAddPdf, label = { Text("PDF") },
                        leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null,
                            modifier = Modifier.size(18.dp)) })
                    AssistChip(onClick = onAddUrl, label = { Text("URL") },
                        leadingIcon = { Icon(Icons.Outlined.Link, null,
                            modifier = Modifier.size(18.dp)) })
                    AssistChip(onClick = onAddText, label = { Text("Paste") },
                        leadingIcon = { Icon(Icons.Outlined.ContentPaste, null,
                            modifier = Modifier.size(18.dp)) })
                }
            }
        }

        items(docs, key = { it.id }) { d ->
            DocRow(ctl, d, isNowPlaying = d.id == playingId, onShowFigures = onShowFigures)
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocRow(
    ctl: UiController,
    d: Document,
    isNowPlaying: Boolean,
    onShowFigures: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val checked = ctl.selected.value.contains(d.id)
    val ctx = LocalContext.current

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isNowPlaying) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { ctl.toggleSelected(d.id) },
                onLongClick = { menuOpen = true }
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Checkbox(checked = checked, onCheckedChange = { ctl.toggleSelected(d.id) })
            Icon(
                when (d.source) {
                    Document.Source.PDF -> Icons.Outlined.PictureAsPdf
                    Document.Source.URL -> Icons.Outlined.Link
                    Document.Source.TEXT -> Icons.Outlined.Article
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(d.title, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(docSubtitle(d), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isNowPlaying) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = "Playing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Play this") },
                        leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                        onClick = { menuOpen = false; ctl.playSingle(d.id) })
                    DropdownMenuItem(
                        text = { Text(if (checked) "Deselect" else "Select") },
                        leadingIcon = { Icon(Icons.Outlined.Check, null) },
                        onClick = { menuOpen = false; ctl.toggleSelected(d.id) })
                    if (d.figures.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Figures (${d.figures.size})") },
                            leadingIcon = { Icon(Icons.Outlined.Image, null) },
                            onClick = { menuOpen = false; onShowFigures(d.id) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null,
                            tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            Toast.makeText(ctx, "Removed ${d.title}", Toast.LENGTH_SHORT).show()
                            ctl.removeDoc(d.id)
                        })
                }
            }
        }
    }
}

private fun docSubtitle(d: Document): String = when (d.source) {
    Document.Source.PDF -> buildString {
        append("PDF")
        if (d.figures.isNotEmpty()) append(" · ${d.figures.size} pages")
        if (d.text.isBlank()) append(" · no audio (no text layer)")
    }
    Document.Source.URL -> runCatching { Uri.parse(d.provenance).host }.getOrNull() ?: d.provenance
    Document.Source.TEXT -> "Pasted · ${d.text.length} chars"
}

// =====================================================================
// ASK + REPLY

@Composable
private fun AskCard(ctl: UiController) {
    var q by remember { mutableStateOf("") }
    val voiceState = ctl.voiceState.value
    // Mirror live on-device speech partials into the question field.
    LaunchedEffect(ctl.voicePartial.value) {
        if (ctl.voicePartial.value.isNotBlank()) q = ctl.voicePartial.value
    }
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = q, onValueChange = { q = it },
                label = {
                    Text(if (voiceState == VoiceInput.State.LISTENING)
                        "Listening… speak your question"
                    else "Ask about your documents…")
                },
                minLines = 1, maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicButton(ctl, size = 46.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (voiceState) {
                        VoiceInput.State.LOADING -> "Loading model…"
                        VoiceInput.State.LISTENING -> "Listening…"
                        VoiceInput.State.TRANSCRIBING -> "Transcribing…"
                        VoiceInput.State.IDLE -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { if (q.isNotBlank()) { ctl.ask(q); q = "" } },
                    enabled = !ctl.busy.value && q.isNotBlank()
                ) {
                    if (ctl.busy.value) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Thinking…")
                    } else Text("Ask")
                }
            }
            ctl.lastZipformer.value?.let { z ->
                Text(
                    "Heard: $z" + (ctl.whisperText.value?.let { w ->
                        if (ctl.whisperBusy.value) "  ·  whisper: decoding…"
                        else "  ·  whisper: $w"
                    } ?: if (ctl.whisperBusy.value) "  ·  whisper: decoding…" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** One turn in the conversation thread: user right-aligned, model full-width. */
@Composable
private fun TurnBubble(turn: UiController.ChatTurn) {
    val isUser = turn.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 18.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer),
            modifier = if (isUser) Modifier.widthIn(max = 300.dp) else Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(turn.text.ifBlank { "(empty reply)" },
                    style = if (isUser) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.bodyLarge)
                if (turn.citedDocTitle != null || turn.citedFigure != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        turn.citedDocTitle?.let {
                            AssistChip(onClick = {}, label = {
                                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }, leadingIcon = {
                                Icon(Icons.Outlined.Description, null, modifier = Modifier.size(16.dp))
                            })
                        }
                        turn.citedFigure?.let {
                            AssistChip(onClick = {}, label = { Text("Fig. $it") },
                                leadingIcon = { Icon(Icons.Outlined.Image, null,
                                    modifier = Modifier.size(16.dp)) })
                        }
                    }
                }
                if (turn.sources.isNotEmpty()) {
                    Column {
                        Text("Web sources", style = MaterialTheme.typography.labelMedium)
                        turn.sources.take(4).forEach { s ->
                            Text("• $s", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// MIC

@Composable
private fun MicButton(ctl: UiController, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ctl.toggleVoice()
        else Toast.makeText(ctx,
            "Voice input needs the microphone (audio stays on-device).",
            Toast.LENGTH_LONG).show()
    }
    val state = ctl.voiceState.value
    val listening = state == VoiceInput.State.LISTENING
    FilledIconButton(
        onClick = {
            if (state != VoiceInput.State.IDLE) ctl.toggleVoice()
            else if (androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) ctl.toggleVoice()
            else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        },
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (listening) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            contentColor = if (listening) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onPrimary
        )
    ) {
        when (state) {
            VoiceInput.State.IDLE ->
                Icon(Icons.Outlined.Mic, "Speak", modifier = Modifier.size(size * 0.5f))
            VoiceInput.State.LISTENING ->
                Icon(Icons.Outlined.Stop, "Stop listening", modifier = Modifier.size(size * 0.5f))
            else -> CircularProgressIndicator(
                modifier = Modifier.size(size * 0.42f),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

// =====================================================================
// NOW PLAYING (playback state)

/**
 * Bottom chrome: the swipe-up sessions handle (entry #2 — swipe up from the
 * bottom edge, or tap the pill) with the now-playing bar stacked above it
 * whenever audio is live.
 */
@Composable
private fun BottomChrome(ctl: UiController, playbackVisible: Boolean, onOpenSessions: () -> Unit) {
    Surface(
        tonalElevation = if (playbackVisible) 6.dp else 2.dp,
        shadowElevation = if (playbackVisible) 6.dp else 0.dp,
        color = if (playbackVisible) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (playbackVisible) NowPlayingContent(ctl)
            SessionsHandle(onOpenSessions)
        }
    }
}

@Composable
private fun SessionsHandle(onOpen: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -6f) onOpen()   // upward swipe
                }
            }
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun NowPlayingContent(ctl: UiController) {
    val playback = ctl.playback.value
    val title = playback.queue.getOrNull(playback.cursor)?.let { id ->
        ctl.docs.value.firstOrNull { it.id == id }?.title
    } ?: "Speaking…"
    Column(Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (playback.activeTextPreview.isNotBlank()) {
                        Text(playback.activeTextPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (playback.queue.isNotEmpty()) {
                    IconButton(onClick = { ctl.prev() }) {
                        Icon(Icons.Outlined.SkipPrevious, "Previous")
                    }
                }
                FilledIconButton(
                    onClick = { if (playback.isPlaying) ctl.pause() else ctl.resume() },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(if (playback.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Resume")
                }
                if (playback.queue.isNotEmpty()) {
                    IconButton(onClick = { ctl.next() }) {
                        Icon(Icons.Outlined.SkipNext, "Next")
                    }
                }
                IconButton(onClick = { ctl.stop() }) {
                    Icon(Icons.Outlined.Stop, "Stop")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { r ->
                    FilterChip(
                        selected = r == ctl.rate.value,
                        onClick = { ctl.setRate(r) },
                        label = { Text(if (r == 1.0f) "1×" else "${r}×".removeSuffix("f")) })
                }
            }
        }
}

// =====================================================================
// SESSIONS SHEET (past conversations)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsSheet(ctl: UiController, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Sessions", style = MaterialTheme.typography.titleLarge)
            val sessions = ctl.sessions.value
            if (sessions.isEmpty()) {
                Text("No saved conversations yet. Ask something — every conversation lands here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(sessions, key = { it.id }) { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { ctl.resumeSession(s); onClose() }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(relativeTime(s.updatedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { ctl.deleteSession(s) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete session",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun relativeTime(ts: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(ts).toString()

// =====================================================================
// FIGURES DIALOG (per-document, opened from the context menu)

@Composable
private fun FiguresDialog(ctl: UiController, docId: String, onClose: () -> Unit) {
    val doc = ctl.docs.value.firstOrNull { it.id == docId } ?: run { onClose(); return }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(doc.figures, key = { it.id }) { f ->
                    Column {
                        val bmp = remember(f.id) {
                            android.graphics.BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size)
                        }
                        if (bmp != null) {
                            Image(bitmap = bmp.asImageBitmap(),
                                contentDescription = f.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp)))
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                ctl.captionResult.value[f.id] ?: f.caption ?: f.title,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = { ctl.captionFigure(doc.id, f) },
                                enabled = !ctl.captionBusy.value
                            ) { Text(if (ctl.captionBusy.value) "…" else "Caption") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

// =====================================================================
// SETTINGS SHEET

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(ctl: UiController, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = ctl.apiKey.value,
                onValueChange = { ctl.saveApiKey(it) },
                label = { Text("Gemini API key (free @ aistudio.google.com)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ctl.info.value?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    ctl.error.value?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(onClick = { ctl.testConnection() }) { Text("Test") }
            }

            Text("Model", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "gemini-3.6-flash" to "3.6 Flash",
                    "gemini-3.8-flash" to "3.8 Flash",
                    "gemini-flash-lite-latest" to "Flash-Lite",
                ).forEach { (id, label) ->
                    FilterChip(selected = ctl.model.value == id,
                        onClick = { ctl.saveModel(id) },
                        label = { Text(label) })
                }
            }

            HorizontalDivider()
            SettingSwitch(
                title = "Speak replies",
                subtitle = "Read the agent's answers aloud",
                checked = ctl.speakReplies.value,
                onChange = { ctl.toggleSpeakReplies(it) })
            SettingSwitch(
                title = "Web search",
                subtitle = "Let the agent search Google for ancillary info",
                checked = ctl.webSearch.value,
                onChange = { ctl.toggleWebSearch(it) })
            SettingSwitch(
                title = "Use my documents",
                subtitle = "Ground answers in the selected library docs",
                checked = ctl.useGrounding.value,
                onChange = { ctl.toggleGrounding(it) })
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// =====================================================================
// ADD DIALOGS

@Composable
private fun UrlDialog(ctl: UiController, onClose: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("Add article URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text("https://en.wikipedia.org/wiki/…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("Title (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { if (url.isNotBlank()) { ctl.addUrl(url.trim(), title); onClose() } }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
private fun TextDialog(ctl: UiController, onClose: () -> Unit) {
    var t by remember { mutableStateOf("My notes") }
    var body by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("Paste text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = t, onValueChange = { t = it }, label = { Text("Title") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Body") },
                    minLines = 3, modifier = Modifier.fillMaxWidth().height(160.dp))
            }
        },
        confirmButton = {
            Button(onClick = { if (body.isNotBlank()) { ctl.addText(t, body); onClose() } }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
