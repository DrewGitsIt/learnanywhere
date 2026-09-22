package com.learnanywhere.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.learnanywhere.data.Document
import com.learnanywhere.speech.VoiceInput

/**
 * Karaoke wash. A translucent primary reads as a highlighter over both the
 * secondaryContainer of a reply bubble and the tertiaryContainer of the
 * reading card, in light and dark alike — a solid container colour does not.
 */
@Composable
internal fun highlightTone(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)

/**
 * The sentence currently being spoken AS PART OF THE CURRENT ANSWER, or null.
 * Gated on the utterance id so a read-with-me section or a tool cue can't
 * light up a reply bubble that happens to contain the same words.
 */
@Composable
internal fun spokenReplyText(ctl: UiController): String? {
    val p = ctl.playback.value
    return p.activeText.takeIf {
        it.isNotBlank() && p.activeUtterance != null &&
            p.activeUtterance in ctl.replyUtteranceIds.value
    }
}

/** Text with [highlight] washed in, or plain text when there's nothing to mark. */
@Composable
internal fun highlighted(text: String, highlight: String?): AnnotatedString {
    val tone = highlightTone()
    val range = highlight?.let { highlightRange(text, it) }
    return remember(text, range, tone) {
        if (range == null) AnnotatedString(text)
        else buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(background = tone), range.first, range.last + 1)
        }
    }
}

// =====================================================================
// ASK

@Composable
internal fun AskCard(ctl: UiController, onAskStarted: () -> Unit = {}) {
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
                MicButton(ctl, size = 46.dp, onStart = onAskStarted)
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
                    onClick = {
                        if (q.isNotBlank()) { onAskStarted(); ctl.ask(q); q = "" }
                    },
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

/**
 * One turn in the conversation thread: user right-aligned, model full-width.
 * [highlight] is the sentence being spoken right now (null for every bubble
 * but the live one); a cited page rides along as a tappable thumbnail.
 */
@Composable
internal fun TurnBubble(
    ctl: UiController,
    turn: UiController.ChatTurn,
    highlight: String? = null,
    onOpenPage: (String, Int) -> Unit = { _, _ -> }
) {
    val isUser = turn.role == "user"
    val display = turn.text.ifBlank { "(empty reply)" }
    val body = highlighted(display, if (isUser) null else highlight)
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
                Text(body,
                    style = if (isUser) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.bodyLarge)
                if (!isUser) CitedPage(ctl, turn, onOpenPage)
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
                                leadingIcon = { Icon(Icons.Outlined.ImageIcon, null,
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

/**
 * The cited figure as the page it actually lives on (DESIGN §6.5). Silent
 * when the document has since been removed or the page doesn't resolve — a
 * citation is a hint, never a guarantee.
 */
@Composable
private fun CitedPage(
    ctl: UiController,
    turn: UiController.ChatTurn,
    onOpenPage: (String, Int) -> Unit
) {
    if (turn.citedPage == null) return       // the common case: nothing to resolve
    val docs = ctl.docs.value
    val idx = resolveCitedDocIndex(docs.map { it.title }, turn.citedDocTitle) ?: return
    val doc = docs[idx]
    val page = clampCitedPage(turn.citedPage, doc.figures.size) ?: return
    PageThumb(doc, page, height = 160.dp, modifier = Modifier.fillMaxWidth()) {
        onOpenPage(doc.id, page)
    }
}

/** A rendered PDF page, cropped from the top like a document preview. */
@Composable
internal fun PageThumb(
    doc: Document,
    page: Int,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val fig = doc.figures.getOrNull(page - 1) ?: return
    val bmp = remember(fig.id) {
        android.graphics.BitmapFactory.decodeByteArray(fig.bytes, 0, fig.bytes.size)
    } ?: return
    Column(modifier) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page $page of ${doc.title}",
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
        )
        Text("Page $page — tap to enlarge",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp))
    }
}

// =====================================================================
// PAGE VIEWER / FIGURES

/** One page, full width, with the same on-demand caption the figures list has. */
@Composable
internal fun PageViewerDialog(ctl: UiController, docId: String, page: Int, onClose: () -> Unit) {
    val doc = ctl.docs.value.firstOrNull { it.id == docId } ?: run { onClose(); return }
    val fig = doc.figures.getOrNull(page - 1) ?: run { onClose(); return }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${doc.title} — page $page", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val bmp = remember(fig.id) {
                    android.graphics.BitmapFactory.decodeByteArray(fig.bytes, 0, fig.bytes.size)
                }
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(),
                        contentDescription = fig.title,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ctl.captionResult.value[fig.id] ?: fig.caption ?: fig.title,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { ctl.captionFigure(doc.id, fig) },
                        enabled = !ctl.captionBusy.value
                    ) { Text(if (ctl.captionBusy.value) "…" else "Caption") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
internal fun FiguresDialog(ctl: UiController, docId: String, onClose: () -> Unit) {
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
