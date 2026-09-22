package com.learnanywhere.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The read-along card: the whole section, the sentence being spoken washed in,
 * and the PDF page it came from (DESIGN §6.4/§6.5).
 */
@Composable
internal fun ReadWithMeCard(
    ctl: UiController,
    r: UiController.ReadingState,
    onOpenPage: (String, Int) -> Unit
) {
    val section = r.sections[r.index]
    val playback = ctl.playback.value
    // Gated on the chain's utterance ids: an answer spoken mid-session must
    // not light up the section text it was asked about.
    val spoken = playback.activeText.takeIf {
        it.isNotBlank() && playback.activeUtterance in ctl.readingUtteranceIds.value
    }
    val range = spoken?.let { highlightRange(section, it) }
    val body = highlighted(section, spoken)
    val scroll = rememberScrollState()

    // Keep the highlight in view. The offset is estimated from the character
    // position rather than measured: a TextLayoutResult would be exact, but
    // proportional scrolling is stable across rate changes and never fights
    // the user's own scrolling more than one animation at a time.
    LaunchedEffect(range?.first, scroll.maxValue) {
        val start = range?.first ?: return@LaunchedEffect
        if (scroll.maxValue <= 0 || section.isEmpty()) return@LaunchedEffect
        val target = (scroll.maxValue * (start.toFloat() / section.length)).toInt()
        scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoStories, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.docTitle, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Section ${r.index + 1} of ${r.sections.size}" +
                        (ctl.readingPage.value?.let { " · page $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { ctl.endReadWithMe() }) {
                    Icon(Icons.Outlined.Close, contentDescription = "End reading")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 220.dp)
                        .verticalScroll(scroll))
                val doc = ctl.docs.value.firstOrNull { d -> d.id == r.docId }
                val page = clampCitedPage(ctl.readingPage.value, doc?.figures?.size ?: 0)
                if (doc != null && page != null) {
                    PageThumb(doc, page, height = 128.dp, modifier = Modifier.width(92.dp)) {
                        onOpenPage(doc.id, page)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { ctl.prevSection() }, enabled = r.index > 0) {
                    Icon(Icons.Outlined.SkipPrevious, null, modifier = Modifier.size(18.dp))
                }
                OutlinedButton(onClick = { ctl.nextSection() }) {
                    Icon(Icons.Outlined.SkipNext, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("Next")
                }
                Text("Speak to interrupt — ask, or say “next section”.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}
