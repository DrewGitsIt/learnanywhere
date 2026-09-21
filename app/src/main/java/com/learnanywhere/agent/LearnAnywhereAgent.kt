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
    private val appCtx: android.content.Context,
    private val webSearch: () -> Boolean = { true }
) {
    data class AgentReply(
        val text: String,
        val citedDocId: String?,
        val citedFigure: String?,
        val usage: String?,
        val sources: List<String> = emptyList()
    )

    /**
     * Multi-turn conversation history (user/model text turns only — the doc
     * grounding parts are re-sent fresh each call). Process-lifetime;
     * [clearHistory] starts a new conversation.
     */
    private val history = ArrayDeque<Gemini.Message>()

    fun clearHistory() = history.clear()

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

            val searchOn = webSearch()
            val sys = buildString {
                appendLine("You are LearnAnywhere, a hands-free study companion. " +
                        "You are grounded in the attached documents.")
                appendLine("Every answer must:")
                appendLine("  1. Cite the source document by title.")
                appendLine("  2. If the answer rests on a figure/table, name it.")
                appendLine("  3. Be plain and spoken-friendly.")
                appendLine("  4. Stay concise — 2 to 5 sentences unless asked.")
                appendLine("  5. If outside the attached docs, say so, then answer from knowledge, tagged (general).")
                if (searchOn)
                    appendLine("  6. Use Google Search for ancillary or current information; " +
                            "prefer the attached documents for questions about them.")
                appendLine("The user may be speaking; questions can have transcription errors — " +
                        "interpret them charitably.")
                systemExtra?.let { appendLine(); appendLine(it) }
            }

            try {
                val userMsg = Gemini.Message("user", listOf(Gemini.Part(text = question)))
                val reply = client.generateText(
                    contents = grounding + history.toList() + listOf(userMsg),
                    systemInstruction = sys,
                    enableSearch = searchOn
                )
                history.addLast(userMsg)
                history.addLast(Gemini.Message("model", listOf(Gemini.Part(text = reply.text))))
                while (history.size > MAX_HISTORY_TURNS * 2) history.removeFirst()
                val fig = Regex("Figure[\\s:-]*([A-Za-z0-9_\\-./]+)", RegexOption.IGNORE_CASE).find(reply.text)
                val citedDoc = docsHere.firstOrNull { d ->
                    reply.text.contains(d.title, ignoreCase = true) ||
                            (d.provenance.isNotBlank() && reply.text.contains(d.provenance, ignoreCase = true))
                }?.id
                val usage = if (reply.promptTokens != null || reply.completionTokens != null)
                    "in=${reply.promptTokens ?: "?"} out=${reply.completionTokens ?: "?"}" else null
                AgentReply(reply.text, citedDoc, fig?.groupValues?.get(1), usage, reply.sources)
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

    companion object {
        /** Kept small: doc grounding is re-sent every call, so context adds up fast. */
        private const val MAX_HISTORY_TURNS = 10
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
