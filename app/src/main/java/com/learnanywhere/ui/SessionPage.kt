package com.learnanywhere.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.learnanywhere.app.db.ConversationRow

/**
 * Pages 1..N of the pager: one saved conversation each, newest first.
 *
 * The page the user has settled on is the CURRENT session and renders the live
 * thread (plus the ask field); its neighbours render their stored transcript,
 * read without touching the agent — resuming happens on settle, in
 * [LearnAnywhereScreen], not on every pre-composed neighbour.
 */
@Composable
internal fun SessionPage(
    ctl: UiController,
    row: ConversationRow,
    isCurrent: Boolean,
    onOpenPage: (String, Int) -> Unit
) {
    var stored by remember(row.id) { mutableStateOf<List<UiController.ChatTurn>>(emptyList()) }
    LaunchedEffect(row.id, row.updatedAt, isCurrent) {
        if (!isCurrent) stored = ctl.loadTranscript(row.id)
    }
    val turns = if (isCurrent) ctl.thread.value else stored
    val spoken = spokenReplyText(ctl)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(relativeTime(row.updatedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (isCurrent) item { AskCard(ctl) }

        itemsIndexed(turns) { i, turn ->
            TurnBubble(ctl, turn,
                highlight = spoken.takeIf {
                    isCurrent && turn.role == "model" && i == turns.lastIndex
                },
                onOpenPage = onOpenPage)
        }
        if (isCurrent) {
            ctl.streamingAnswer.value?.let { live ->
                item { TurnBubble(ctl, UiController.ChatTurn("model", live), highlight = spoken) }
            }
            ctl.error.value?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        } else if (turns.isEmpty()) {
            item {
                Text("Loading…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}
