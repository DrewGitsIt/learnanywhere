package com.learnanywhere.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.learnanywhere.data.Document

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnAnywhereScreen(ctl: UiController) {
    var openDialog by remember { mutableStateOf<DialogType>(DialogType.None) }

    // SAF: pick a PDF
    val pickPdf: ActivityResultLauncher<String> = rememberLauncherForActivityResult(
        contract = object : ActivityResultContract<String, Uri?>() {
            override fun createIntent(ctx: Context, input: String): Intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = input
                }
            override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
                if (resultCode == Activity.RESULT_OK && intent != null) intent.data else null
        }
    ) { uri -> uri?.let { ctl.addPdf(it) } }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚗 LearnAnywhere", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Text("Your study companion for long drives.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            DocsSection(ctl, onAddPdf = { pickPdf.launch("application/pdf") },
                onAddUrl = { openDialog = DialogType.Url },
                onAddText = { openDialog = DialogType.Text })
            Spacer(Modifier.height(16.dp))
            AskSection(ctl)
            Spacer(Modifier.height(16.dp))
            ListenSection(ctl)
            Spacer(Modifier.height(16.dp))
            SettingsSection(ctl)
            Spacer(Modifier.height(24.dp))
        }
    }

    when (openDialog) {
        DialogType.Url  -> UrlDialog(ctl) { openDialog = DialogType.None }
        DialogType.Text -> TextDialog(ctl) { openDialog = DialogType.None }
        DialogType.None -> {}
    }
}

private enum class DialogType { None, Url, Text }

// =====================================================================

@Composable
private fun DocsSection(ctl: UiController, onAddPdf: () -> Unit, onAddUrl: () -> Unit, onAddText: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Documents in this session", style = MaterialTheme.typography.titleLarge)
            if (ctl.docs.value.isEmpty()) {
                Text("Add a PDF, URL, or pasted text. Each is selectable as a " +
                        "grounding source for the agent **or** an audiobook chapter.",
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                ctl.docs.value.forEach { d ->
                    val checked = ctl.selected.value.contains(d.id)
                    ListItem(
                        headlineContent = { Text(d.title, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(d.provenance.ifBlank { d.source.name }) },
                        leadingContent = {
                            Checkbox(checked = checked, onCheckedChange = { ctl.toggleSelected(d.id) })
                        },
                        trailingContent = {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            TextButton(onClick = { ctx.let { Toast.makeText(it, "Removed ${d.title}", Toast.LENGTH_SHORT).show() }; ctl.removeDoc(d.id) }) { Text("✕") }
                        }
                    )
                    if (d.figures.isNotEmpty()) {
                        d.figures.forEach { f ->
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size)
                            if (bmp != null) {
                                Image(bitmap = bmp.asImageBitmap(),
                                    contentDescription = f.title,
                                    modifier = Modifier.fillMaxWidth().wrapContentHeight())
                            }
                            // (a) caption: show the cached caption if any, else a "Caption" button
                            val cap = ctl.captionResult.value[f.id]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = cap ?: (f.caption ?: "—"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { ctl.captionFigure(d.id, f) },
                                    enabled = !ctl.captionBusy.value,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text(if (ctl.captionBusy.value) "…" else "Caption",
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddPdf) { Text("Add PDF") }
                OutlinedButton(onClick = onAddUrl) { Text("Add URL") }
                OutlinedButton(onClick = onAddText) { Text("Paste") }
            }
        }
    }
}

@Composable
private fun AskSection(ctl: UiController) {
    var q by remember { mutableStateOf("") }
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ask the agent", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = q, onValueChange = { q = it },
                label = { Text("Ask about your documents…") },
                placeholder = { Text("e.g. What does Figure 2 show?") },
                minLines = 2, maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { if (q.isNotBlank()) { ctl.ask(q); q = "" } },
                enabled = !ctl.busy.value,
                modifier = Modifier.align(Alignment.End)) {
                Text(if (ctl.busy.value) "Thinking…" else "Ask")
            }

            ctl.reply.value?.let { r ->
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(r.text, style = MaterialTheme.typography.bodyLarge)
                        if (r.citedFigure != null) {
                            Spacer(Modifier.height(6.dp))
                            Text("Referenced: **Figure ${r.citedFigure}**",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (r.usage != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(r.usage, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            ctl.error.value?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ListenSection(ctl: UiController) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Audiobook (on-device TTS)", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ctl.listen() },
                    enabled = ctl.selected.value.isNotEmpty()) { Text("▶ Play selected") }
                OutlinedButton(onClick = { ctl.pause()  }) { Text("⏸ Pause") }
                OutlinedButton(onClick = { ctl.prev()   }) { Text("◀ Prev") }
                OutlinedButton(onClick = { ctl.next()   }) { Text("Next ▶") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Speed:")
                listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { rate ->
                    FilterChip(selected = rate == ctl.rate.value,
                        onClick = { ctl.setRate(rate) },
                        label = { Text("×${rate}") })
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(ctl: UiController) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = ctl.apiKey.value,
                onValueChange = { ctl.saveApiKey(it) },
                label = { Text("Gemini API key  (free @ aistudio.google.com)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { ctl.testConnection() },
                modifier = Modifier.align(Alignment.End)) { Text("Test connection") }
            ctl.info.value?.let {
                Text(it, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium)
            }
            ctl.error.value?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Model:")
                listOf("gemini-2.5-flash" to "2.5 Flash", "gemini-2.5-pro" to "2.5 Pro").forEach { (id, label) ->
                    AssistChip(onClick = { ctl.saveModel(id) },
                        label = { Text(label) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (ctl.model.value == id)
                                MaterialTheme.colorScheme.primaryContainer else
                                MaterialTheme.colorScheme.surface
                        ))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Grounding:")
                AssistChip(onClick = { ctl.toggleGrounding(true) }, label = { Text("On") },
                    colors = AssistChipDefaults.assistChipColors(containerColor =
                        if (ctl.useGrounding.value) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface))
                AssistChip(onClick = { ctl.toggleGrounding(false) }, label = { Text("Off") },
                    colors = AssistChipDefaults.assistChipColors(containerColor =
                        if (!ctl.useGrounding.value) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface))
            }
        }
    }
}

// =====================================================================

@Composable
private fun UrlDialog(ctl: UiController, onClose: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("Add article URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        dismissButton = { TextButton(onClick = { onClose() }) { Text("Cancel") } })
}

@Composable
private fun TextDialog(ctl: UiController, onClose: () -> Unit) {
    var t by remember { mutableStateOf("My notes") }
    var body by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("Paste text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        dismissButton = { TextButton(onClick = { onClose() }) { Text("Cancel") } })
}
