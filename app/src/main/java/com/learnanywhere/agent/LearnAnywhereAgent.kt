package com.learnanywhere.agent

import android.content.Context
import android.net.Uri
import com.learnanywhere.data.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestration: user question + selected documents -> grounded Gemini reply.
 *
 * Hardening (DESIGN.md §3.7):
 *  - PDFs are uploaded once to the free Files API (48 h retention) and
 *    referenced by URI each turn instead of re-sending megabytes of base64;
 *    URIs are cached in SharedPreferences and invalidated on 4xx (with an
 *    automatic inline-bytes retry).
 *  - Replies use structured output (JSON schema) so the cited document and
 *    figure come back as fields, not regex guesses over prose.
 *  - The system prompt is sectioned and voice-aware (answers are spoken).
 */
class LearnAnywhereAgent(
    private val api: () -> String,
    private val model: () -> String,
    private val docs: () -> List<Document>,
    private val appCtx: Context,
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

    /** docId -> "uri|expiresAtMillis" for Files-API uploads (48 h server TTL). */
    private val filePrefs by lazy {
        appCtx.getSharedPreferences("learnanywhere_files", Context.MODE_PRIVATE)
    }

    suspend fun ask(question: String, systemExtra: String? = null): AgentReply =
        withContext(Dispatchers.IO) {
            val client = Gemini(api, model)
            val docsHere = docs()

            fun buildGrounding(inlineOnly: Boolean): Pair<List<Gemini.Message>, Boolean> {
                var usedFileUris = false
                val msgs = buildList {
                    docsHere.forEach { d ->
                        if (d.isPdf) {
                            val msg = if (inlineOnly) inlinePdfPart(client, d)
                                      else pdfGroundingPart(client, d)
                            if (msg != null) {
                                add(msg)
                                if (msg.parts.any { it.fileUri != null }) usedFileUris = true
                            }
                        } else if (d.text.isNotBlank()) {
                            add(client.textPart(d.title, d.text, mimeFor(d)))
                        }
                    }
                }
                return msgs to usedFileUris
            }

            val searchOn = webSearch()
            val sys = systemPrompt(searchOn, systemExtra)
            val userMsg = Gemini.Message("user", listOf(Gemini.Part(text = question)))

            try {
                fun call(contents: List<Gemini.Message>, withSchema: Boolean) =
                    client.generateText(
                        contents = contents,
                        systemInstruction = sys,
                        enableSearch = searchOn,
                        responseSchemaJson = if (withSchema) RESPONSE_SCHEMA else null
                    )

                val (grounding, usedFiles) = buildGrounding(inlineOnly = false)
                var contents = grounding + history.toList() + listOf(userMsg)
                val reply = try {
                    call(contents, withSchema = true)
                } catch (e: GeminiError) {
                    when {
                        // A cached Files-API URI may have expired server-side:
                        // invalidate and retry once with inline bytes.
                        usedFiles && e.code in 400..404 -> {
                            docsHere.forEach { filePrefs.edit().remove(it.id).apply() }
                            contents = buildGrounding(inlineOnly = true).first +
                                    history.toList() + listOf(userMsg)
                            try {
                                call(contents, withSchema = true)
                            } catch (e2: GeminiError) {
                                if (e2.code == 400) call(contents, withSchema = false) else throw e2
                            }
                        }
                        // Some model/tool combos may reject responseSchema:
                        // degrade to plain text (regex citations still work).
                        e.code == 400 -> call(contents, withSchema = false)
                        else -> throw e
                    }
                }

                val parsed = ReplyJson.parse(reply.text)
                val answer = parsed?.answer ?: reply.text
                history.addLast(userMsg)
                history.addLast(Gemini.Message("model", listOf(Gemini.Part(text = answer))))
                while (history.size > MAX_HISTORY_TURNS * 2) history.removeFirst()

                val citedDoc = parsed?.citedDocument?.let { title ->
                    docsHere.firstOrNull { it.title.equals(title, ignoreCase = true) }
                        ?: docsHere.firstOrNull { it.title.contains(title, ignoreCase = true) ||
                                title.contains(it.title, ignoreCase = true) }
                }?.id ?: docsHere.firstOrNull { d ->
                    answer.contains(d.title, ignoreCase = true)
                }?.id
                val fig = parsed?.citedFigure
                    ?: Regex("Figure[\\s:-]*([A-Za-z0-9_\\-./]+)", RegexOption.IGNORE_CASE)
                        .find(answer)?.groupValues?.get(1)
                val usage = if (reply.promptTokens != null || reply.completionTokens != null)
                    "in=${reply.promptTokens ?: "?"} out=${reply.completionTokens ?: "?"}" else null
                AgentReply(answer, citedDoc, fig, usage, reply.sources)
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
                client.generateText(listOf(msg), systemInstruction = sys, maxTokens = 512)
                    .text.trim().ifBlank { "(no caption)" }
            } catch (e: Throwable) {
                "caption failed: " + (e.message ?: "unknown")
            }
        }

    // ------------------------------------------------------------------
    // Grounding helpers

    /** Files-API grounding: cached URI if fresh, else upload; inline on failure. */
    private fun pdfGroundingPart(client: Gemini, d: Document): Gemini.Message? {
        val now = System.currentTimeMillis()
        filePrefs.getString(d.id, null)?.split("|")?.let { cached ->
            if (cached.size == 2 && (cached[1].toLongOrNull() ?: 0L) > now) {
                return client.filePart(d.title, cached[0])
            }
        }
        val bytes = loadPdf(d)
        if (bytes.isEmpty()) return null
        return try {
            val up = client.uploadFile(bytes, "application/pdf", d.title)
            // Server keeps files 48 h; refresh a little early.
            filePrefs.edit().putString(d.id, up.uri + "|" + (now + 47L * 3600 * 1000)).apply()
            client.filePart(d.title, up.uri)
        } catch (t: Throwable) {
            client.pdfPart(d.title, bytes)
        }
    }

    private fun inlinePdfPart(client: Gemini, d: Document): Gemini.Message? {
        val bytes = loadPdf(d)
        return if (bytes.isEmpty()) null else client.pdfPart(d.title, bytes)
    }

    // ------------------------------------------------------------------

    private fun systemPrompt(searchOn: Boolean, systemExtra: String?): String = buildString {
        appendLine("# Role")
        appendLine("You are LearnAnywhere, a hands-free study companion. The user is often")
        appendLine("listening while driving or otherwise occupied, not reading a screen.")
        appendLine()
        appendLine("# Context")
        appendLine("The user's selected library documents are attached to this conversation.")
        appendLine("Questions may come from speech recognition and can contain transcription")
        appendLine("errors — interpret them charitably.")
        appendLine()
        appendLine("# Output rules")
        appendLine("- Reply as JSON matching the response schema: `answer` is your reply;")
        appendLine("  `cited_document` is the exact title of the attached document the answer")
        appendLine("  rests on (omit when none); `cited_figure` names the figure or table it")
        appendLine("  rests on (omit when none).")
        appendLine("- The answer is spoken aloud by text-to-speech: plain conversational")
        appendLine("  prose. No markdown, no bullet lists, no headings, never read URLs")
        appendLine("  aloud. 2–4 sentences unless the user asks for detail.")
        appendLine("- If the answer is not in the attached documents, say so briefly, then")
        appendLine("  answer from general knowledge" +
                (if (searchOn) " or web search." else "."))
        if (searchOn) {
            appendLine()
            appendLine("# Tools")
            appendLine("Use Google Search for ancillary or current information; prefer the")
            appendLine("attached documents for questions about their content.")
        }
        systemExtra?.let { appendLine(); appendLine(it) }
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

    companion object {
        /** Kept small: doc grounding is re-sent every call, so context adds up fast. */
        private const val MAX_HISTORY_TURNS = 10

        /** Structured-output schema for [ask] replies (Gemini 3 allows this with tools). */
        internal const val RESPONSE_SCHEMA = """{"type":"object","properties":{""" +
                """"answer":{"type":"string","description":"The reply, written to be spoken aloud"},""" +
                """"cited_document":{"type":"string","description":"Exact title of the attached source document, if any"},""" +
                """"cited_figure":{"type":"string","description":"Figure or table the answer rests on, if any"}},""" +
                """"required":["answer"]}"""
    }
}

/** Pure parser for the structured [LearnAnywhereAgent.RESPONSE_SCHEMA] replies. */
object ReplyJson {
    data class Parsed(val answer: String, val citedDocument: String?, val citedFigure: String?)

    /** Null when the text isn't the expected JSON (caller falls back to raw text). */
    fun parse(text: String): Parsed? = try {
        val o = org.json.JSONObject(text.trim())
        Parsed(
            o.getString("answer"),
            o.optString("cited_document").ifBlank { null },
            o.optString("cited_figure").ifBlank { null }
        )
    } catch (_: Throwable) {
        null
    }
}
