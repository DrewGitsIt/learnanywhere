package com.learnanywhere.core

import java.util.Base64
import kotlin.random.Random

/**
 * Pure (Android-free) core: request-body shape + URL→text normalization.
 * Kept in its own file so a plain JVM `kotlin` test can drive it and the
 * JVM test can assert the exact JSON a real network call would send.
 */
object GeminiBodyBuilder {

    data class Part(
        val text: String? = null,
        val mime: String? = null,
        val dataB64: String? = null
    )

    data class Message(val role: String, val parts: List<Part> = listOf())

    /**
     * Build the JSON body for `:generateContent`. Pure; no Android types.
     * (The real caller wraps this in OkHttp on the Android side.)
     */
    fun generateContent(
        contents: List<Message>,
        systemInstruction: String?,
        temperature: Float,
        topP: Float,
        maxOutputTokens: Int,
        enableGoogleSearch: Boolean = false
    ): String {
        val sb = StringBuilder().append("{")
        sb.append("\"contents\":[")
        contents.forEachIndexed { i, m ->
            if (i > 0) sb.append(",")
            sb.append("{\"role\":\"").append(m.role).append("\",\"parts\":[")
            m.parts.forEachIndexed { j, p ->
                if (j > 0) sb.append(",")
                sb.append(p.toJson())
            }
            sb.append("]}")
        }
        sb.append("]")
        if (systemInstruction != null) {
            sb.append(",\"system_instruction\":{\"parts\":[{\"text\":\"")
                .append(escape(systemInstruction)).append("\"}],\"role\":\"user\"}")
        }
        sb.append(",\"generationConfig\":{\"temperature\":")
            .append(temperature).append(",\"topP\":").append(topP)
            .append(",\"maxOutputTokens\":").append(maxOutputTokens).append("}")
        if (enableGoogleSearch) {
            // Google Search grounding — free tier includes it (DESIGN.md §3.4).
            sb.append(",\"tools\":[{\"google_search\":{}}]")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun Part.toJson(): String = buildString {
        val parts = ArrayList<String>()
        text?.let { parts.add("\"text\":\"${escape(it)}\"") }
        if (mime != null && dataB64 != null) {
            parts.add("\"inlineData\":{\"mimeType\":\"$mime\",\"data\":\"$dataB64\"}")
        }
        append("{").append(parts.joinToString(",")).append("}")
    }

    fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (c.code < 0x20) append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else append(c)
                }
            }
        }
    }

    fun pdfPart(base64: String) = Part(
        text = "Attached document (PDF).",
        mime = "application/pdf",
        dataB64 = base64
    )

    fun textPart(content: String, title: String): Part = Part(
        text = "Attached reference from \"$title\"",
        mime = "text/plain",
        dataB64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
    )
}

// ----------------------------------------------------------------------
// URL -> text  (the part that powers "arbitrary content from a URL")
// ----------------------------------------------------------------------

object UrlText {
    fun stripHtml(html: String): String =
        html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<noscript[^>]*>[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<(br|/p|/div|/li|/h[1-6]|/ul|/ol)\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
}
