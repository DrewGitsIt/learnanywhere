package com.learnanywhere.agent

import android.net.Uri
import com.learnanywhere.data.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestration: user question + selected documents  ->  grounded Gemini reply.
 */
class LearnAnywhereAgent(
    private val api: () -> String,
    private val model: () -> String,
    private val docs: () -> List<Document>,
    private val appCtx: android.content.Context
) {
    data class AgentReply(
        val text: String,
        val citedDocId: String?,
        val citedFigure: String?,
        val usage: String?
    )

    suspend fun ask(question: String, systemExtra: String? = null): AgentReply =
        withContext(Dispatchers.IO) {
            val client = Gemini(api, model)
            val docsHere = docs()
            val grounding = buildList {
                docsHere.forEach { d ->
                    if (d.isPdf) {
                        val bytes = loadPdf(d)
                        if (bytes.isNotEmpty()) add(client.pdfPart(d.title, bytes))
                    } else if (d.text.isNotBlank()) {
                        add(client.textPart(d.title, d.text, mimeFor(d)))
                    }
                }
            }

            val sys = buildString {
                appendLine("You are LearnAnywhere, an on-vehicle study companion. " +
                        "You are grounded in the attached documents.")
                appendLine("Every answer must:")
                appendLine("  1. Cite the source document by title.")
                appendLine("  2. If the answer rests on a figure/table, name it.")
                appendLine("  3. Be plain and spoken-friendly.")
                appendLine("  4. Stay concise — 2 to 5 sentences unless asked.")
                appendLine("  5. If outside the attached docs, say so, then answer from knowledge, tagged (general).")
                systemExtra?.let { appendLine(); appendLine(it) }
            }

            try {
                val reply = client.generateText(
                    contents = grounding + listOf(Gemini.Message("user", listOf(Gemini.Part(text = question)))),
                    systemInstruction = sys
                )
                val fig = Regex("Figure[\\s:-]*([A-Za-z0-9_\\-./]+)", RegexOption.IGNORE_CASE).find(reply.text)
                val citedDoc = docsHere.firstOrNull { d ->
                    reply.text.contains(d.title, ignoreCase = true) ||
                            (d.provenance.isNotBlank() && reply.text.contains(d.provenance, ignoreCase = true))
                }?.id
                val usage = if (reply.promptTokens != null || reply.completionTokens != null)
                    "in=${reply.promptTokens ?: "?"} out=${reply.completionTokens ?: "?"}" else null
                AgentReply(reply.text, citedDoc, fig?.groupValues?.get(1), usage)
            } catch (e: Exception) {
                AgentReply("Connection problem: " + (e.message ?: "unknown"), null, null, null)
            }
        }

    /** On-demand caption for a rendered PDF page. */
    suspend fun captionFigure(doc: Document, figure: com.learnanywhere.data.Figure): String =
        withContext(Dispatchers.IO) {
            val client = Gemini(api, model)
            val sys = com.learnanywhere.core.Figures.captionPrompt(doc.title, figure.title)
            val msg = Gemini.Message("user", listOf(
                Gemini.Part(text = "Caption this figure:"),
                Gemini.Part(mime = figure.mimeType, dataB64 = java.util.Base64.getEncoder().encodeToString(figure.bytes))
            ))
            try {
                // 48 was enough pre-thinking-models; Gemini 3.x spends thought
                // tokens from the same budget, so give it headroom.
                client.generateText(listOf(msg), systemInstruction = sys, maxTokens = 512)
                    .text.trim().ifBlank { "(no caption)" }
            } catch (e: Throwable) {
                "caption failed: " + (e.message ?: "unknown")
            }
        }

    private fun mimeFor(d: Document) = when (d.source) {
        Document.Source.TEXT  -> "text/plain"
        Document.Source.URL   -> if (d.provenance.endsWith(".html")) "text/html" else "text/plain"
        Document.Source.PDF   -> "application/pdf"
    }

    private fun loadPdf(d: Document): ByteArray {
        val loc = d.pdfLocator
        if (loc.isNullOrBlank()) return byteArrayOf()
        return try {
            val uri = Uri.parse(loc)
            appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
        } catch (t: Throwable) {
            byteArrayOf()
        }
    }
}
