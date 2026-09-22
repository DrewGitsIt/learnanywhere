package com.learnanywhere.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Settings, in sections (DESIGN §6.7): AI model · Tools · Voice & playback.
 * A flat list of eight switches made "which of these is the voice one?" a
 * hunt every time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(ctl: UiController, onClose: () -> Unit) {
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

            SectionHeader("AI model")
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

            SectionHeader("Tools")
            OutlinedTextField(
                value = ctl.tavilyKey.value,
                onValueChange = { ctl.saveTavilyKey(it) },
                label = { Text("Tavily API key (free @ tavily.com) — web search") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SettingSwitch(
                title = "Web search",
                subtitle = "Let the agent search the web for ancillary info",
                checked = ctl.webSearch.value,
                onChange = { ctl.toggleWebSearch(it) })
            SettingSwitch(
                title = "Use my documents",
                subtitle = "Ground answers in the selected library docs",
                checked = ctl.useGrounding.value,
                onChange = { ctl.toggleGrounding(it) })

            SectionHeader("Voice & playback")
            SettingSwitch(
                title = "Speak replies",
                subtitle = "Read the agent's answers aloud",
                checked = ctl.speakReplies.value,
                onChange = { ctl.toggleSpeakReplies(it) })
            SettingSwitch(
                title = "Neural voice (Piper)",
                subtitle = "Natural on-device voice; first use loads the model (~3 s)",
                checked = ctl.neuralVoice.value,
                onChange = { ctl.toggleNeuralVoice(it) })
            SettingSwitch(
                title = "Voice interrupt",
                subtitle = "While it's talking, just speak to interrupt (on-device VAD)",
                checked = ctl.bargeIn.value,
                onChange = { ctl.toggleBargeIn(it) })
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Speech speed", style = MaterialTheme.typography.bodyLarge)
                Text("Applies to everything spoken, and is remembered",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SpeedChips(ctl)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        HorizontalDivider()
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
