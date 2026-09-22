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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.learnanywhere.speech.VoiceInput
import kotlinx.coroutines.launch

/**
 * The whole app is one horizontal pager (DESIGN §6.1):
 *  - page 0     → [HomePage]: hero-or-library, ask field, `+ Add`, the library
 *  - pages 1..N → [SessionPage]: saved conversations, newest first
 *
 * Home always exists, so the entry hero is one swipe away instead of being
 * lost the moment the library stops being empty. Settling on a session page
 * resumes that session; asking from Home starts a new one and rides to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnAnywhereScreen(ctl: UiController) {
    var dialog by remember { mutableStateOf(DialogType.None) }
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var figuresDocId by remember { mutableStateOf<String?>(null) }
    var pageViewer by remember { mutableStateOf<PageRef?>(null) }

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

    val currentId = ctl.currentSessionId.value
    val playback = ctl.playback.value
    val playbackVisible = playback.isPlaying || playback.queue.isNotEmpty()
    val scope = rememberCoroutineScope()

    // Page order is STABLE. Room sorts sessions by recency, so answering
    // inside an older session would slide every page along under the user —
    // the page they were reading would become somebody else's transcript.
    // New sessions join at the front; the rest keep the page they had.
    var order by remember { mutableStateOf<List<String>>(emptyList()) }
    val live = ctl.sessions.value
    LaunchedEffect(live) {
        val ids = live.map { it.id }
        val kept = order.filter { it in ids }
        order = ids.filter { it !in kept } + kept
    }
    val pages = remember(order, live) {
        val byId = live.associateBy { it.id }
        order.mapNotNull { byId[it] }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size + 1 })

    // Armed when the user asks from Home; disarmed by the jump itself or by
    // the user settling somewhere else first.
    var awaitingNewSession by remember { mutableStateOf(false) }

    LaunchedEffect(awaitingNewSession, currentId, pages) {
        if (!awaitingNewSession || currentId == null) return@LaunchedEffect
        val idx = pages.indexOfFirst { it.id == currentId }
        if (idx >= 0) {
            awaitingNewSession = false
            pagerState.animateScrollToPage(idx + 1)
        }
    }

    // Settling on a session page resumes it (agent history included). Never
    // mid-stream: a resume would swap the thread out from under the answer.
    LaunchedEffect(pagerState.settledPage, pages, ctl.busy.value) {
        if (pagerState.settledPage != 0) awaitingNewSession = false
        val row = pages.getOrNull(pagerState.settledPage - 1) ?: return@LaunchedEffect
        if (row.id != ctl.currentSessionId.value && !ctl.busy.value) ctl.resumeSession(row)
    }

    val jumpTo: (String) -> Unit = { id ->
        val idx = pages.indexOfFirst { it.id == id }
        if (idx >= 0) scope.launch { pagerState.animateScrollToPage(idx + 1) }
    }

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
                    if (pagerState.currentPage != 0 || ctl.thread.value.isNotEmpty()) {
                        IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(0) } }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "New chat")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        // Bottom chrome hosts the swipe-up sessions handle and, when audio is
        // live, the now-playing bar above it.
        bottomBar = { BottomChrome(ctl, playbackVisible) { showSessions = true } }
    ) { pad ->
        HorizontalPager(
            state = pagerState,
            key = { page -> pages.getOrNull(page - 1)?.id ?: "home" },
            modifier = Modifier.padding(pad).fillMaxSize().imePadding()
        ) { page ->
            val row = pages.getOrNull(page - 1)
            if (row == null) {
                HomePage(
                    ctl,
                    onAdd = { showAdd = true },
                    onShowFigures = { figuresDocId = it },
                    onOpenSessions = { showSessions = true },
                    onOpenPage = { docId, p -> pageViewer = PageRef(docId, p) },
                    onAskStarted = { awaitingNewSession = true })
            } else {
                SessionPage(
                    ctl, row,
                    isCurrent = row.id == currentId,
                    onOpenPage = { docId, p -> pageViewer = PageRef(docId, p) })
            }
        }
    }

    when (dialog) {
        DialogType.Url -> UrlDialog(ctl) { dialog = DialogType.None }
        DialogType.Text -> TextDialog(ctl) { dialog = DialogType.None }
        DialogType.None -> {}
    }
    if (showAdd) {
        AddSheet(
            onAddPdf = { pickPdf.launch("application/pdf") },
            onAddUrl = { dialog = DialogType.Url },
            onAddText = { dialog = DialogType.Text },
            onClose = { showAdd = false })
    }
    if (showSettings) SettingsSheet(ctl) { showSettings = false }
    if (showSessions) {
        SessionsSheet(ctl, onJump = { jumpTo(it) }) { showSessions = false }
    }
    figuresDocId?.let { id -> FiguresDialog(ctl, id) { figuresDocId = null } }
    pageViewer?.let { ref ->
        PageViewerDialog(ctl, ref.docId, ref.page) { pageViewer = null }
    }
}

/** A page of a document, addressed the way a citation addresses it. */
internal data class PageRef(val docId: String, val page: Int)

internal enum class DialogType { None, Url, Text }

// =====================================================================
// ADD SHEET — the one entry point for content (DESIGN §6.2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddSheet(
    onAddPdf: () -> Unit,
    onAddUrl: () -> Unit,
    onAddText: () -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Add to your library",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
            AddRow(Icons.Outlined.PictureAsPdf, "Add a PDF",
                "A paper or book from this device") { onClose(); onAddPdf() }
            AddRow(Icons.Outlined.Link, "Add from URL",
                "Fetch an article and keep its text") { onClose(); onAddUrl() }
            AddRow(Icons.Outlined.ContentPaste, "Paste text",
                "Notes or anything on your clipboard") { onClose(); onAddText() }
        }
    }
}

@Composable
private fun AddRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            Icon(icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** `+ Add`: hero-sized on an empty Home, compact next to the Library header. */
@Composable
internal fun AddButton(onClick: () -> Unit, hero: Boolean) {
    if (hero) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Add", style = MaterialTheme.typography.titleMedium)
        }
    } else {
        FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add")
        }
    }
}

// =====================================================================
// MIC

/**
 * [onStart] runs only when the mic is about to OPEN — Home uses it to detach
 * from whatever session the pager last resumed, so a spoken question from
 * Home starts a new conversation exactly like a typed one.
 */
@Composable
internal fun MicButton(
    ctl: UiController,
    size: androidx.compose.ui.unit.Dp,
    onStart: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { onStart(); ctl.toggleVoice() }
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
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                onStart(); ctl.toggleVoice()
            } else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
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

internal fun voiceHint(state: VoiceInput.State): String = when (state) {
    VoiceInput.State.IDLE -> "Just ask by voice"
    VoiceInput.State.LOADING -> "Loading speech model…"
    VoiceInput.State.LISTENING -> "Listening — tap to stop"
    VoiceInput.State.TRANSCRIBING -> "Transcribing…"
}

// =====================================================================
// ADD DIALOGS

@Composable
internal fun UrlDialog(ctl: UiController, onClose: () -> Unit) {
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
internal fun TextDialog(ctl: UiController, onClose: () -> Unit) {
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
