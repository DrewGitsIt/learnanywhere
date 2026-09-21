package com.learnanywhere.agent

import com.learnanywhere.core.GeminiBodyBuilder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.util.Base64

/**
 * Thin, minimal client for the Gemini REST `:generateContent` endpoint.
 *
 * The request *body* is built by the pure [GeminiBodyBuilder] (unit-tested in
 * `LearnAnywherePureTest`) so the exact shape we send is the exact shape we assert on.
 * This class only handles the OkHttp plumbing.
 *
 * Why raw OkHttp instead of the official `google-genai` Java SDK?
 *  - The SDK's Android artifact is heavier than we want and its free-tier
 *    handling is opinionated; a 90-line client makes the whole AI surface
 *    of this app one file.
 *  - Free-tier PDF upload is a plain `inline_data` part — no Files API, no
 *    auth dance. (The Files API *is* available on the free tier, but is for
 *    >20 MB; our inline path is simpler and the PDFs we care about — papers,
 *    articles — are well under the 50 MB / 1000-page inline cap.)
 */
class Gemini(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val client: okhttp3.OkHttpClient = OkHttpClientFactory.build()
) {
    data class Part(val text: String? = null,
                    val mime: String? = null,
                    val dataB64: String? = null)

    data class Message(val role: String, val parts: List<Part>)

    /** Grounding part for a PDF (Gemini reads the file directly, including figures). */
    fun pdfPart(title: String, pdfBytes: ByteArray): Message = Message("user", listOf(
        Part(text = "You are grounded in the attached document: \"$title\". Use it to answer.",
             mime = "application/pdf",
             dataB64 = Base64.getEncoder().encodeToString(pdfBytes))
    ))

    /** Grounding part for arbitrary text (article body, pasted notes, etc.). */
    fun textPart(title: String, content: String, mime: String = "text/plain"): Message = Message("user", listOf(
        Part(text = "Attached reference material from: \"$title\" (mime: $mime)",
             mime = mime,
             dataB64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
    ))

    data class Response(
        val text: String,
        val promptTokens: Int?,
        val completionTokens: Int?
    )

    // ------------------------------------------------------------------

    fun generateText(
        contents: List<Message>,
        systemInstruction: String? = null,
        temperature: Float = 0.4f,
        topP: Float = 0.95f,
        maxTokens: Int = 2048
    ): Response {
        val body = GeminiBodyBuilder.generateContent(
            contents = contents.map { m -> m.toBody() },
            systemInstruction = systemInstruction,
            temperature = temperature,
            topP = topP,
            maxOutputTokens = maxTokens
        )
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                (model().ifBlank { DEFAULT_MODEL }) + ":generateContent"
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey().ifBlank { throw IllegalStateException("No API key") })
            .post(RequestBody.create("application/json".toMediaType(), body.toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw GeminiError(resp.code, summarizeError(body, "HTTP ${resp.code}"))
            return parse(body)
        }
    }

    // Gemini 3.x are thinking models: thought tokens count against
    // maxOutputTokens, so tiny budgets return empty text with
    // finishReason=MAX_TOKENS. Keep every budget comfortably above the
    // thinking overhead.
    fun ping(): Response =
        generateText(listOf(Message("user", Part(text = "Reply with the single word OK.").let { listOf(it) })),
            maxTokens = 256)

    // ------------------------------------------------------------------

    private fun Message.toBody() =
        GeminiBodyBuilder.Message(role, parts.map { p ->
            GeminiBodyBuilder.Part(p.text, p.mime, p.dataB64)
        })

    private fun parse(json: String): Response {
        // Minimal JSON reader: just what we need. (No org.json dep; avoids
        // pulling the Android bootclass into a JVM test.)
        val text = extractFirstJsonString(json, "parts")   // first text part of first candidate
        val promptTokens = extractInt(json, "promptTokenCount")
        val completionTokens = extractInt(json, "candidatesTokenCount")
        return Response(text.orEmpty(), promptTokens, completionTokens)
    }

    /** Very-small JSON field extractor — just enough for this response shape. */
    private fun extractFirstJsonString(json: String, key: String): String? {
        // Find the first occurrence of `"key":[{... "text":"..." ...}]`.
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return null
        val after = json.substring(idx + key.length + 3)
        val tStart = after.indexOf("\"text\":\"")
        if (tStart < 0) return null
        val s = tStart + 8
        val sb = StringBuilder()
        var i = s
        while (i < after.length) {
           val c = after[i]
            if (c == '\\' && i + 1 < after.length) {
                val n = after[i+1]
                when (n) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'u' -> {
                        if (i+5 < after.length)
                            sb.append(after.substring(i+2, i+6).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else if (c == '"') break
             else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun extractInt(json: String, key: String): Int? =
        Regex("\"$key\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

    companion object {
        // gemini-2.5-flash 404s for new API keys ("no longer available to new
        // users", verified 2026-09-21); Google's error recommends 3.6-flash.
        const val DEFAULT_MODEL = "gemini-3.6-flash"
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
