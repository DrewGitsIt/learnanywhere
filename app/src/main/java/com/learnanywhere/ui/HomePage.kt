package com.learnanywhere.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.learnanywhere.data.Document
import com.learnanywhere.speech.VoiceInput

/**
 * Page 0 of the pager. Two looks, one page (DESIGN §6.1):
 *  - empty library → the entry hero (it used to vanish for good once the
 *    first document landed; now it is simply what Home looks like when empty)
 *  - otherwise     → ask field, `+ Add`, and the library
 *
 * [onAskStarted] fires when a question is initiated here, arming the pager to
 * ride to the new session's page once its row is persisted.
 */
@Composable
internal fun HomePage(
    ctl: UiController,
    onAdd: () -> Unit,
    onShowFigures: (String) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenPage: (String, Int) -> Unit,
    onAskStarted: () -> Unit
) {
    // Asking from Home opens a new session — except during read-with-me, where
    // the utterance may be a reading command and the answer belongs to the
    // conversation about the passage being read (and the card lives here).
    val startFresh = {
        if (ctl.reading.value == null) { ctl.detachSession(); onAskStarted() }
    }
    if (ctl.docs.value.isEmpty()) HomeHero(ctl, onAdd, onOpenSessions, startFresh)
    else HomeLibrary(ctl, onAdd, onShowFigures, onOpenSessions, onOpenPage, startFresh)
}

@Composable
private fun HomeHero(
    ctl: UiController,
    onAdd: () -> Unit,
    onOpenSessions: () -> Unit,
    startFresh: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
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

            AddButton(onAdd, hero = true)

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f))
                Text("  or  ", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))

            MicButton(ctl, size = 72.dp, onStart = startFresh)
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

            // The live thread stays on Home until its session row lands and
            // the pager carries it to a page of its own.
            if (ctl.currentSessionId.value == null &&
                (ctl.thread.value.isNotEmpty() || ctl.streamingAnswer.value != null)) {
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    LiveThread(ctl) { _, _ -> }
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
private fun HomeLibrary(
    ctl: UiController,
    onAdd: () -> Unit,
    onShowFigures: (String) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenPage: (String, Int) -> Unit,
    startFresh: () -> Unit
) {
    val docs = ctl.docs.value
    val playingId = ctl.playback.value.queue.getOrNull(ctl.playback.value.cursor)
        .takeIf { ctl.playback.value.isPlaying }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { HistoryEntryButton(onOpenSessions) }
        ctl.reading.value?.let { r -> item { ReadWithMeCard(ctl, r, onOpenPage) } }
        item { AskCard(ctl, onAskStarted = startFresh) }

        if (ctl.currentSessionId.value == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveThread(ctl, onOpenPage)
                }
            }
        }
        ctl.error.value?.let {
            item {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp))
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Library", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                AddButton(onAdd, hero = false)
                val n = ctl.selected.value.size
                TextButton(onClick = { ctl.listen() }, enabled = n > 0) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (n > 0) "Play $n" else "Play")
                }
            }
        }

        items(docs, key = { it.id }) { d ->
            DocRow(ctl, d, isNowPlaying = d.id == playingId, onShowFigures = onShowFigures)
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

/** The turns of the not-yet-persisted conversation, plus the streaming bubble. */
@Composable
private fun LiveThread(ctl: UiController, onOpenPage: (String, Int) -> Unit) {
    val turns = ctl.thread.value
    val spoken = spokenReplyText(ctl)
    turns.forEachIndexed { i, turn ->
        TurnBubble(ctl, turn,
            highlight = spoken.takeIf { i == turns.lastIndex && turn.role == "model" },
            onOpenPage = onOpenPage)
    }
    ctl.streamingAnswer.value?.let { live ->
        TurnBubble(ctl, UiController.ChatTurn("model", live), highlight = spoken)
    }
}

@Composable
internal fun HistoryEntryButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onOpen, modifier = modifier) {
        Icon(Icons.Outlined.History, contentDescription = "Sessions")
    }
}

// =====================================================================
// LIBRARY ROWS

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
                    if (d.text.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("Read with me") },
                            leadingIcon = { Icon(Icons.Outlined.AutoStories, null) },
                            onClick = { menuOpen = false; ctl.startReadWithMe(d.id) })
                    }
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
