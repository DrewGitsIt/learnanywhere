package com.learnanywhere.agent

import com.learnanywhere.core.GeminiBodyBuilder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import java.util.Base64

/**
 * Thin, minimal client for the Gemini REST `:generateContent` endpoint plus
 * the Files API (upload once / reference by URI for 48 h).
 *
 * The request *body* is built by the pure [GeminiBodyBuilder] (unit-tested in
 * `LearnAnywherePureTest`) so the exact shape we send is the exact shape we assert on.
 * This class only handles the OkHttp plumbing, retries, and response parsing.
 *
 * Free-tier hardening (DESIGN.md §3.7):
 *  - 429/503 are retried with exponential backoff + jitter, honoring the
 *    server's RetryInfo.retryDelay; daily-quota (RPD) exhaustion is NOT
 *    retried — it surfaces as an error naming the reset time.
 *  - `thinkingLevel: low` bounds Gemini 3 thinking spend (it can't be fully
 *    disabled on 3.7+); thinking tokens count against maxOutputTokens.
 *  - Temperature stays at the model default 1.0 — Gemini 3 docs warn that
 *    lowering it causes looping/degradation.
 */
class Gemini(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val client: okhttp3.OkHttpClient = OkHttpClientFactory.build()
) {
    data class Part(val text: String? = null,
                    val mime: String? = null,
                    val dataB64: String? = null,
                    val fileUri: String? = null,
                    /** Verbatim part JSON (tool-loop echo / functionResponse). */
                    val rawJson: String? = null)

    data class Message(val role: String, val parts: List<Part>)

    /**
     * Grounding message for a PDF already uploaded to the Files API.
     * Preferred over [pdfPart]: no re-upload per turn, cache-friendly.
     */
    fun filePart(title: String, fileUri: String, mime: String = "application/pdf"): Message =
        Message("user", listOf(
            Part(text = "You are grounded in the attached document: \"$title\". Use it to answer."),
            Part(mime = mime, fileUri = fileUri)
        ))

    /** Inline-bytes grounding for a PDF (fallback when the Files API fails). */
    fun pdfPart(title: String, pdfBytes: ByteArray): Message = Message("user", listOf(
        // A Part is a union type: text and inlineData must be SEPARATE parts.
        Part(text = "You are grounded in the attached document: \"$title\". Use it to answer."),
        Part(mime = "application/pdf",
             dataB64 = Base64.getEncoder().encodeToString(pdfBytes))
    ))

    /** Grounding message for arbitrary text (article body, pasted notes, etc.). */
    fun textPart(title: String, content: String, mime: String = "text/plain"): Message = Message("user", listOf(
        Part(text = "Attached reference material from: \"$title\" (mime: $mime)"),
        Part(mime = mime,
             dataB64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
    ))

    /** A custom-tool invocation requested by the model. */
    data class FunctionCall(val name: String, val id: String?, val argsJson: String)

    data class Response(
        val text: String,
        val promptTokens: Int?,
        val completionTokens: Int?,
        /** "title — uri" per grounding source when search grounding was used. */
        val sources: List<String> = emptyList(),
        /** Custom-tool calls the model wants executed (empty = final answer). */
        val functionCalls: List<FunctionCall> = emptyList(),
        /**
         * The candidate's parts, verbatim JSON. When functionCalls is
         * non-empty these MUST be echoed back as the model turn unaltered —
         * they carry thoughtSignature and functionCall.id.
         */
        val rawParts: List<String> = emptyList()
    )

    // ------------------------------------------------------------------

    suspend fun generateText(
        contents: List<Message>,
        systemInstruction: String? = null,
        temperature: Float = 1.0f,
        topP: Float = 0.95f,
        maxTokens: Int = 4096,
        enableSearch: Boolean = false,
        thinkingLevel: String? = "low",
        responseSchemaJson: String? = null,
        functionDeclarationsJson: String? = null
    ): Response {
        val body = GeminiBodyBuilder.generateContent(
            contents = contents.map { m -> m.toBody() },
            systemInstruction = systemInstruction,
            temperature = temperature,
            topP = topP,
            maxOutputTokens = maxTokens,
            enableGoogleSearch = enableSearch,
            thinkingLevel = thinkingLevel,
            responseSchemaJson = responseSchemaJson,
            functionDeclarationsJson = functionDeclarationsJson
        )
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                (model().ifBlank { DEFAULT_MODEL }) + ":generateContent"
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey().ifBlank { throw IllegalStateException("No API key") })
            .post(RequestBody.create("application/json".toMediaType(), body.toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
            .build()
        TranscriptLog.log("request", body)

        var attempt = 0
        while (true) {
            attempt++
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    TranscriptLog.log("response", respBody)
                    return parse(respBody)
                }
                TranscriptLog.log("error(${resp.code})", respBody)
                // A 429 without RetryInfo is not transient (e.g. a tool quota
                // with limit 0, like free-tier search grounding) — retrying
                // the same shape only burns time. 503s are always transient.
                val retriable = attempt < MAX_ATTEMPTS && (resp.code == 503 ||
                        (resp.code == 429 && !isDailyQuota(respBody) &&
                                retryDelayMs(respBody) != null))
                if (!retriable) {
                    val msg = if (resp.code == 429 && isDailyQuota(respBody))
                        "daily free-tier quota exhausted (resets midnight Pacific)"
                    else summarizeError(respBody, "HTTP ${resp.code}")
                    throw GeminiError(resp.code, msg)
                }
                val backoff = 1000L shl (attempt - 1)   // 1s, 2s, 4s…
                val delay = maxOf(retryDelayMs(respBody) ?: 0L, backoff)
                    .coerceAtMost(30_000L) + (0..250).random()
                kotlinx.coroutines.delay(delay)
            }
        }
    }

    suspend fun ping(): Response =
        generateText(listOf(Message("user", Part(text = "Reply with the single word OK.").let { listOf(it) })),
            maxTokens = 256)

    // ------------------------------------------------------------------
    // Files API (free tier; 48 h retention; 50 MB / 1,000 pages per PDF)

    data class UploadedFile(val name: String, val uri: String, val state: String)

    /**
     * Upload bytes via the resumable protocol (start → upload+finalize),
     * then wait for the file to become ACTIVE. Call on IO.
     */
    suspend fun uploadFile(bytes: ByteArray, mime: String, displayName: String): UploadedFile {
        val key = apiKey().ifBlank { throw IllegalStateException("No API key") }
        val meta = "{\"file\":{\"display_name\":\"" +
                GeminiBodyBuilder.escape(displayName) + "\"}}"
        val start = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files")
            .header("x-goog-api-key", key)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(RequestBody.create("application/json".toMediaType(), meta))
            .build()
        val uploadUrl = client.newCall(start).execute().use { r ->
            if (!r.isSuccessful) throw GeminiError(r.code, "file upload start failed: HTTP ${r.code}")
            r.header("X-Goog-Upload-URL")
                ?: throw GeminiError(r.code, "file upload start returned no upload URL")
        }
        val up = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Command", "upload, finalize")
            .header("X-Goog-Upload-Offset", "0")
            .post(RequestBody.create(mime.toMediaType(), bytes))
            .build()
        val json = client.newCall(up).execute().use { r ->
            val b = r.body?.string() ?: ""
            if (!r.isSuccessful) throw GeminiError(r.code, summarizeError(b, "file upload failed: HTTP ${r.code}"))
            b
        }
        var f = org.json.JSONObject(json).getJSONObject("file").let {
            UploadedFile(it.getString("name"), it.getString("uri"), it.optString("state"))
        }
        // PDFs usually go ACTIVE immediately; poll briefly if still processing.
        var polls = 0
        while (f.state == "PROCESSING" && polls < 15) {
            kotlinx.coroutines.delay(1000)
            polls++
            val get = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/${f.name}")
                .header("x-goog-api-key", key).get().build()
            client.newCall(get).execute().use { r ->
                if (r.isSuccessful) {
                    val o = org.json.JSONObject(r.body?.string() ?: "{}")
                    f = UploadedFile(o.optString("name", f.name),
                        o.optString("uri", f.uri), o.optString("state", f.state))
                }
            }
        }
        if (f.state == "FAILED") throw GeminiError(500, "file processing failed for \"$displayName\"")
        TranscriptLog.log("upload", "${f.name} ${f.state} $displayName")
        return f
    }

    // ------------------------------------------------------------------

    private fun Message.toBody() =
        GeminiBodyBuilder.Message(role, parts.map { p ->
            GeminiBodyBuilder.Part(p.text, p.mime, p.dataB64, p.fileUri, p.rawJson)
        })

    /**
     * Parse a generateContent response with org.json. (The REST API returns
     * pretty-printed JSON — a substring scanner looking for `"text":"` never
     * matches `"text": "`. Real parser, no more cleverness.) Internal so the
     * JVM test can feed it captured response bodies.
     */
    /**
     * Streamed variant: POSTs to `:streamGenerateContent?alt=sse` and calls
     * [onDelta] with each text fragment as it arrives (thought parts and
     * functionCall parts produce no deltas). Returns the same accumulated
     * [Response] shape as [generateText]. Retries only before the stream
     * starts; a mid-stream drop throws GeminiError(0, …) — callers fall back
     * to the non-streamed path.
     */
    suspend fun generateTextStreamed(
        contents: List<Message>,
        systemInstruction: String? = null,
        temperature: Float = 1.0f,
        topP: Float = 0.95f,
        maxTokens: Int = 4096,
        enableSearch: Boolean = false,
        thinkingLevel: String? = "low",
        responseSchemaJson: String? = null,
        functionDeclarationsJson: String? = null,
        onDelta: (String) -> Unit
    ): Response {
        val body = GeminiBodyBuilder.generateContent(
            contents = contents.map { m -> m.toBody() },
            systemInstruction = systemInstruction,
            temperature = temperature,
            topP = topP,
            maxOutputTokens = maxTokens,
            enableGoogleSearch = enableSearch,
            thinkingLevel = thinkingLevel,
            responseSchemaJson = responseSchemaJson,
            functionDeclarationsJson = functionDeclarationsJson
        )
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                (model().ifBlank { DEFAULT_MODEL }) + ":streamGenerateContent?alt=sse"
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey().ifBlank { throw IllegalStateException("No API key") })
            .post(RequestBody.create("application/json".toMediaType(), body.toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
            .build()
        TranscriptLog.log("request(stream)", body)

        var attempt = 0
        while (true) {
            attempt++
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                val b = resp.use { it.body?.string() ?: "" }
                TranscriptLog.log("error(${resp.code})", b)
                // Same rule as generateText: bare 429s (no RetryInfo) are
                // permanent for this request shape — fail fast.
                val retriable = attempt < MAX_ATTEMPTS && (resp.code == 503 ||
                        (resp.code == 429 && !isDailyQuota(b) &&
                                retryDelayMs(b) != null))
                if (!retriable) {
                    val msg = if (resp.code == 429 && isDailyQuota(b))
                        "daily free-tier quota exhausted (resets midnight Pacific)"
                    else summarizeError(b, "HTTP ${resp.code}")
                    throw GeminiError(resp.code, msg)
                }
                val backoff = 1000L shl (attempt - 1)
                kotlinx.coroutines.delay(maxOf(retryDelayMs(b) ?: 0L, backoff)
                    .coerceAtMost(30_000L) + (0..250).random())
                continue
            }
            // Stream started — accumulate chunks.
            val sb = StringBuilder()
            val fcalls = ArrayList<FunctionCall>()
            val rawParts = ArrayList<String>()
            val sources = LinkedHashSet<String>()
            var promptTokens: Int? = null
            var completionTokens: Int? = null
            var finishReason: String? = null
            try {
                resp.use { r ->
                    val source = r.body?.source() ?: throw GeminiError(0, "empty stream body")
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        val c = parseInternal(payload, requireText = false)
                        if (c.text.isNotEmpty()) { sb.append(c.text); onDelta(c.text) }
                        fcalls.addAll(c.functionCalls)
                        rawParts.addAll(c.rawParts)
                        sources.addAll(c.sources)
                        c.promptTokens?.let { promptTokens = it }
                        c.completionTokens?.let { completionTokens = it }
                        c.finishReason?.let { finishReason = it }
                    }
                }
            } catch (e: java.io.IOException) {
                throw GeminiError(0, "stream interrupted: ${e.message}")
            }
            TranscriptLog.log("response(stream)", sb.toString().take(2000))
            if (sb.isBlank() && fcalls.isEmpty() && finishReason != null) {
                throw GeminiError(200, "empty reply (finishReason=$finishReason)")
            }
            // Streaming yields one rawPart per SSE chunk — a part list the
            // model never produced. Coalesce text fragments back into the
            // aggregate part shape so the tool loop's verbatim echo really
            // is verbatim (Gemini 3 contract).
            return Response(sb.toString(), promptTokens, completionTokens,
                sources.toList(), fcalls, com.learnanywhere.core.ToolWire.coalesceTextParts(rawParts))
        }
    }

    internal fun parse(json: String): Response = parseInternal(json, requireText = true).toResponse()

    private class Accum(
        val text: String,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val sources: List<String>,
        val functionCalls: List<FunctionCall>,
        val rawParts: List<String>,
        val finishReason: String?
    ) {
        fun toResponse() = Response(text, promptTokens, completionTokens,
            sources, functionCalls, rawParts)
    }

    private fun parseInternal(json: String, requireText: Boolean): Accum {
        val o = org.json.JSONObject(json)
        val sb = StringBuilder()
        var finishReason: String? = null
        val sources = ArrayList<String>()
        val fcalls = ArrayList<FunctionCall>()
        val rawParts = ArrayList<String>()
        o.optJSONArray("candidates")?.let { cands ->
            if (cands.length() > 0) {
                val c0 = cands.getJSONObject(0)
                finishReason = c0.optString("finishReason").ifBlank { null }
                c0.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        rawParts.add(p.toString())
                        if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
                        p.optJSONObject("functionCall")?.let { fc ->
                            fcalls.add(FunctionCall(
                                fc.getString("name"),
                                fc.optString("id").ifBlank { null },
                                fc.optJSONObject("args")?.toString() ?: "{}"
                            ))
                        }
                    }
                }
                // Search-grounding citations, when the tool was used.
                c0.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { gc ->
                    for (i in 0 until gc.length()) {
                        gc.getJSONObject(i).optJSONObject("web")?.let { w ->
                            val title = w.optString("title")
                            val uri = w.optString("uri")
                            if (uri.isNotBlank())
                                sources.add(if (title.isNotBlank()) "$title — $uri" else uri)
                        }
                    }
                }
            }
        }
        val usage = o.optJSONObject("usageMetadata")
        val text = sb.toString()
        if (requireText && text.isBlank() && fcalls.isEmpty() && finishReason != null) {
            // e.g. MAX_TOKENS with the whole budget spent on thinking.
            throw GeminiError(200, "empty reply (finishReason=$finishReason)")
        }
        return Accum(
            text,
            usage?.takeIf { it.has("promptTokenCount") }?.getInt("promptTokenCount"),
            usage?.takeIf { it.has("candidatesTokenCount") }?.getInt("candidatesTokenCount"),
            sources,
            fcalls,
            rawParts,
            finishReason
        )
    }

    companion object {
        // gemini-2.5-flash 404s for new API keys ("no longer available to new
        // users", verified 2026-09-21); Google's error recommends 3.6-flash.
        const val DEFAULT_MODEL = "gemini-3.6-flash"
        private const val MAX_ATTEMPTS = 3
    }
}

class GeminiError(val code: Int, override val message: String) : Exception("Gemini($code): $message")

private fun summarizeError(body: String, fallback: String): String {
    // Look for a `"message":"..."` inside a nested `"error"` object.
    val status = Regex("\"status\":\"([A-Z_]+)\"").find(body)?.groupValues?.get(1)
    val code = Regex("\"code\":(\\d+)").find(body)?.groupValues?.get(1)
    val msg = Regex("\"message\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    if (code == null && status == null && msg == null) {
        // Not the standard error envelope — surface a snippet of whatever the
        // server actually sent, so failures are diagnosable from the UI.
        val snippet = body.trim().take(200)
        return if (snippet.isEmpty()) fallback else "$fallback: $snippet"
    }
    return listOfNotNull(code, status, msg).joinToString(" ")
}

/** Server-suggested retry delay from a 429 body's RetryInfo, in ms. */
internal fun retryDelayMs(body: String): Long? =
    Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(body)
        ?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() }

/** True when the tripped quota is the per-day one — retrying is pointless. */
internal fun isDailyQuota(body: String): Boolean =
    body.contains("PerDay") || body.contains("per day", ignoreCase = true)
