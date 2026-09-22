package com.learnanywhere.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bottom chrome: the swipe-up sessions handle (a fast jump list for long
 * histories — the pager is the primary way between sessions) with the
 * now-playing bar stacked above it whenever audio is live.
 */
@Composable
internal fun BottomChrome(ctl: UiController, playbackVisible: Boolean, onOpenSessions: () -> Unit) {
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
                    // The full sentence being spoken, not a 96-char prefix.
                    if (playback.activeText.isNotBlank()) {
                        Text(playback.activeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
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
            SpeedChips(ctl)
        }
}

/** Speech speed. Also in Settings, so it's reachable while nothing is playing. */
@Composable
internal fun SpeedChips(ctl: UiController) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { r ->
            FilterChip(
                selected = r == ctl.rate.value,
                onClick = { ctl.setRate(r) },
                label = { Text(speedLabel(r)) })
        }
    }
}

// =====================================================================
// SESSIONS SHEET (fast jump list)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsSheet(ctl: UiController, onJump: (String) -> Unit, onClose: () -> Unit) {
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
                Text("Swipe between sessions, or jump straight to one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                // Jump only: settling on the page resumes it.
                                .clickable { onJump(s.id); onClose() }
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

internal fun relativeTime(ts: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(ts).toString()
