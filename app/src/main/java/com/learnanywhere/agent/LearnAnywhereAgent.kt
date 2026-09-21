package com.learnanywhere.agent

import android.content.Context
import android.net.Uri
import com.learnanywhere.core.ToolWire
import com.learnanywhere.data.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
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
    private val webSearch: () -> Boolean = { true },
    private val tools: AgentTools? = null
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

    /**
     * Serializes turns: barge-in cancels the old ask, but cancellation is
     * cooperative — without this, a dying turn could interleave [history]
     * writes with its replacement.
     */
    private val askMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Free-tier keys can have ZERO Google Search grounding quota: any request
     * with the google_search tool attached 429s instantly (bare body, no
     * RetryInfo) while identical requests without it succeed (observed
     * 2026-09-21). Once tripped, asks drop the search tool for the rest of
     * the process instead of failing; a restart re-probes in case quota came
     * back.
     */
    @Volatile
    private var searchQuotaTripped = false

    fun clearHistory() = history.clear()

    /** Reload history from a persisted session (role "user"/"model" + text). */
    fun restoreHistory(turns: List<Pair<String, String>>) {
        history.clear()
        turns.takeLast(MAX_HISTORY_TURNS * 2).forEach { (role, text) ->
            history.addLast(Gemini.Message(role, listOf(Gemini.Part(text = text))))
        }
    }

    /** docId -> "uri|expiresAtMillis" for Files-API uploads (48 h server TTL). */
    private val filePrefs by lazy {
        appCtx.getSharedPreferences("learnanywhere_files", Context.MODE_PRIVATE)
    }

    suspend fun ask(
        question: String,
        systemExtra: String? = null,
        /** Voice loop v2: raw text deltas as the reply streams (SSE). */
        onAnswerDelta: ((String) -> Unit)? = null,
        /**
         * Fires before each additional model round of the tool loop so the
         * UI can reset its per-round streaming state (the answer extractor
         * is a one-shot machine; reusing it across rounds swallows the
         * final answer or speaks raw JSON).
         */
        onRoundStart: (() -> Unit)? = null,
        /** Fires as each tool starts executing — feed for "Searching…" cues. */
        onToolCall: ((String) -> Unit)? = null
    ): AgentReply =
        withContext(Dispatchers.IO) { askMutex.withLock {
            val client = Gemini(api, model)
            val docsHere = docs()

            suspend fun buildGrounding(inlineOnly: Boolean): Pair<List<Gemini.Message>, Boolean> {
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

            var searchNow = webSearch() && !searchQuotaTripped
            var sys = systemPrompt(searchNow)
            // Prompt-cache alignment (DESIGN §5): the request prefix — system
            // prompt, grounding docs, history — must stay byte-stable across
            // turns for Gemini's implicit cache. Volatile per-turn context
            // (reading cursor, grounding toggle) therefore rides LAST, as a
            // user-role context message right before the question.
            val extrasMsg = systemExtra?.let {
                Gemini.Message("user", listOf(Gemini.Part(
                    text = "[Session context — not the user speaking] $it")))
            }
            val userMsg = Gemini.Message("user", listOf(Gemini.Part(text = question)))

            try {
                var useSchema = true
                // Snapshot once per ask: the Tavily key / web-search toggle
                // can change mid-loop, and rounds 2..N must declare the same
                // tool set the echoed functionCalls came from.
                val decls = tools?.declarationsJson
                suspend fun call(contents: List<Gemini.Message>): Gemini.Response {
                    val schema = if (useSchema) RESPONSE_SCHEMA else null
                    return if (onAnswerDelta != null) {
                        try {
                            client.generateTextStreamed(
                                contents = contents,
                                systemInstruction = sys,
                                enableSearch = searchNow,
                                responseSchemaJson = schema,
                                functionDeclarationsJson = decls,
                                onDelta = onAnswerDelta)
                        } catch (e: GeminiError) {
                            // Mid-stream drop (code 0): finish non-streamed.
                            if (e.code == 0) client.generateText(
                                contents = contents, systemInstruction = sys,
                                enableSearch = searchNow, responseSchemaJson = schema,
                                functionDeclarationsJson = decls)
                            else throw e
                        }
                    } else client.generateText(
                        contents = contents,
                        systemInstruction = sys,
                        enableSearch = searchNow,
                        responseSchemaJson = schema,
                        functionDeclarationsJson = decls
                    )
                }

                // See [searchQuotaTripped]: a 429 on a search-enabled request
                // is almost always the search-grounding quota, not the model's
                // (plain requests keep working). Degrade and go on; if the
                // model quota really is gone the retry 429s too and surfaces.
                suspend fun callDroppingSearchOn429(contents: List<Gemini.Message>): Gemini.Response =
                    try {
                        call(contents)
                    } catch (e: GeminiError) {
                        if (e.code == 429 && searchNow) {
                            searchQuotaTripped = true
                            searchNow = false
                            sys = systemPrompt(false)
                            call(contents)
                        } else throw e
                    }

                val (grounding, usedFiles) = buildGrounding(inlineOnly = false)
                var base = grounding + history.toList() +
                        listOfNotNull(extrasMsg) + listOf(userMsg)
                var reply = try {
                    callDroppingSearchOn429(base)
                } catch (e: GeminiError) {
                    when {
                        // A cached Files-API URI may have expired server-side:
                        // invalidate and retry once with inline bytes.
                        usedFiles && e.code in 400..404 -> {
                            docsHere.forEach { filePrefs.edit().remove(it.id).apply() }
                            base = buildGrounding(inlineOnly = true).first +
                                    history.toList() + listOfNotNull(extrasMsg) + listOf(userMsg)
                            try {
                                callDroppingSearchOn429(base)
                            } catch (e2: GeminiError) {
                                if (e2.code == 400) { useSchema = false; call(base) } else throw e2
                            }
                        }
                        // Some model/tool combos may reject responseSchema:
                        // degrade to plain text (regex citations still work).
                        e.code == 400 -> { useSchema = false; call(base) }
                        else -> throw e
                    }
                }

                // ---- tool loop (DESIGN §3.7): execute functionCalls until the
                // model answers in text. The model's own parts are echoed back
                // VERBATIM (thoughtSignature + functionCall.id must round-trip);
                // tool failures return to the model as {error}, never thrown.
                val toolTurns = ArrayList<Gemini.Message>()
                var iterations = 0
                while (reply.functionCalls.isNotEmpty() && iterations < MAX_TOOL_ITERATIONS) {
                    iterations++
                    val budgetExhausted = iterations == MAX_TOOL_ITERATIONS
                    toolTurns.add(Gemini.Message("model",
                        reply.rawParts.map { Gemini.Part(rawJson = it) }))
                    // Execute parallel calls CONCURRENTLY (the model batches
                    // e.g. search_papers + list_library in one reply; serial
                    // execution is dead air the user hears).
                    val responses = coroutineScope {
                        reply.functionCalls.map { fc ->
                            async {
                                onToolCall?.invoke(fc.name)
                                val result =
                                    if (budgetExhausted) "{\"error\":\"tool budget exhausted\"}"
                                    else tools?.execute(fc.name, fc.argsJson)
                                        ?: "{\"error\":\"no tools available\"}"
                                Gemini.Part(rawJson =
                                    ToolWire.functionResponsePart(fc.id, fc.name, result))
                            }
                        }.map { it.await() }
                    }
                    val parts =
                        if (!budgetExhausted) responses
                        // Last round: tell the model the budget is gone so it
                        // answers from what it has instead of looping. The
                        // pending calls still get (error) responses — leaving
                        // them unanswered is a contract violation.
                        else responses + Gemini.Part(text = "Tool budget exhausted — answer " +
                                "the user now from what you already have; do not call more tools.")
                    toolTurns.add(Gemini.Message("user", parts))
                    onRoundStart?.invoke()
                    reply = callDroppingSearchOn429(base + toolTurns)
                }
                if (reply.functionCalls.isNotEmpty()) {
                    // Even the forced-answer round tried to call tools: fail
                    // loudly rather than return an empty answer after side
                    // effects (downloads) already landed.
                    throw GeminiError(200,
                        "the model kept requesting tools after $MAX_TOOL_ITERATIONS rounds")
                }

                val parsed = ReplyJson.parse(reply.text)
                val answer = parsed?.answer ?: reply.text
                if (answer.isNotBlank()) {
                    // Never write a blank model turn — it would be re-sent as
                    // poisoned history on every later ask this session.
                    history.addLast(userMsg)
                    history.addLast(Gemini.Message("model", listOf(Gemini.Part(text = answer))))
                    // Trim in BLOCKS of four turns: dropping one turn per ask
                    // would shift the prompt prefix every turn and forfeit
                    // all implicit-cache hits in long sessions.
                    if (history.size > MAX_HISTORY_TURNS * 2) {
                        repeat(HISTORY_TRIM_TURNS * 2) {
                            if (history.isNotEmpty()) history.removeFirst()
                        }
                    }
                }

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
                    "in=${reply.promptTokens ?: "?"} out=${reply.completionTokens ?: "?"}" +
                            (reply.cachedTokens?.let { " cached=$it" } ?: "") else null
                AgentReply(answer, citedDoc, fig, usage, reply.sources)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
            // Everything else propagates: converting failures into a normal-
            // looking AgentReply made TTS read HTTP dumps aloud and wrote
            // them into the conversation DB as model turns (audit finding).
            // UiController owns turning errors into a short spoken sentence.
        } }

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
    private suspend fun pdfGroundingPart(client: Gemini, d: Document): Gemini.Message? {
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

    private fun systemPrompt(searchOn: Boolean): String = buildString {
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
        if (searchOn || tools != null) {
            appendLine()
            appendLine("# Tools")
            if (searchOn) {
                appendLine("Use Google Search for ancillary or current information; prefer the")
                appendLine("attached documents for questions about their content.")
            }
            if (tools != null) {
                appendLine("When the user asks you to find or fetch a paper: search_papers,")
                appendLine("pick the best match (prefer one with pdf_url), check list_library")
                appendLine("for duplicates, then download_document with that pdf_url. If")
                appendLine("search_papers finds nothing, retry once with different terms")
                appendLine("before giving up. For current events or general facts not in the")
                appendLine("attached documents, use search_web if it is available. Confirm out")
                appendLine("loud what was added and whether it can be read aloud.")
            }
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

    companion object {
        /** Kept small: doc grounding is re-sent every call, so context adds up fast. */
        private const val MAX_HISTORY_TURNS = 10
        /** Turns dropped per trim — in a block, for prompt-prefix stability. */
        private const val HISTORY_TRIM_TURNS = 4

        /** Hard cap on tool-loop rounds (best practice: 5–10 for a small agent). */
        private const val MAX_TOOL_ITERATIONS = 5

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
