package com.learnanywhere.core

/**
 * Pure (Android-free) helpers for the Tavily web-search API
 * (https://docs.tavily.com/documentation/api-reference/endpoint/search).
 *
 * Pure JDK + our own code (JSON via [MiniJson], string escaping via
 * [GeminiBodyBuilder.escape]) so a plain `kotlin` test can drive it directly —
 * same split as GeminiBodyBuilder vs Gemini, and same reader PaperSearch uses.
 */
object TavilySearch {

    private const val MAX_RESULTS_RETURNED = 5

    /** The documented Tavily search request body — just the fields we use. */
    fun buildRequestBody(query: String, maxResults: Int): String = buildString {
        append("{\"query\":\"").append(GeminiBodyBuilder.escape(query)).append("\"")
        append(",\"max_results\":").append(maxResults)
        append(",\"include_answer\":true")
        append(",\"search_depth\":\"basic\"}")
    }

    /**
     * Reshape Tavily's response into the compact, model-facing JSON the
     * `search_web` tool returns. Tolerant of missing/null/malformed input —
     * Tavily's shape isn't a stable contract and a parse hiccup must never throw.
     */
    fun parseResponse(json: String, maxSnippetChars: Int = 400): String {
        val root = MiniJson.parse(json) as? Map<*, *>

        val answer = (root?.get("answer") as? String)?.trim().orEmpty()
        val rawResults = (root?.get("results") as? List<*>).orEmpty()
        val items = rawResults.filterIsInstance<Map<*, *>>().take(MAX_RESULTS_RETURNED)

        val resultsJson = items.joinToString(",") { r ->
            val title = (r["title"] as? String).orEmpty()
            val url = (r["url"] as? String).orEmpty()
            val content = (r["content"] as? String).orEmpty()
            "{\"title\":\"${GeminiBodyBuilder.escape(title)}\"" +
                ",\"url\":\"${GeminiBodyBuilder.escape(url)}\"" +
                ",\"snippet\":\"${GeminiBodyBuilder.escape(truncate(content, maxSnippetChars))}\"}"
        }

        return buildString {
            append("{")
            if (answer.isNotEmpty()) append("\"answer\":\"").append(GeminiBodyBuilder.escape(answer)).append("\",")
            append("\"results\":[").append(resultsJson).append("]")
            if (items.isEmpty() && answer.isEmpty()) append(",\"note\":\"no web results\"")
            append("}")
        }
    }

    /** Truncate at a word boundary, appending "…" only when content was actually cut. */
    private fun truncate(content: String, maxChars: Int): String {
        if (content.length <= maxChars) return content
        val cut = content.substring(0, maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        val boundary = if (lastSpace > 0) cut.substring(0, lastSpace) else cut
        return boundary.trimEnd() + "…"
    }

    /** One-line, model-facing summary of a non-2xx Tavily response. */
    fun summarizeError(code: Int, body: String): String = when (code) {
        401, 403 -> "web search failed: HTTP $code (check the Tavily API key in Settings)"
        429, 432 -> "web search failed: HTTP $code (Tavily free-tier quota exceeded)"
        else -> {
            val msg = extractErrorMessage(body)
            "web search failed: HTTP $code (${(msg ?: body).take(120)})"
        }
    }

    /** Best-effort `"error"`/`"detail"` field pull; falls back to null on anything malformed. */
    private fun extractErrorMessage(body: String): String? {
        val obj = MiniJson.parse(body) as? Map<*, *> ?: return null
        val msg = (obj["error"] as? String) ?: (obj["detail"] as? String)
        return msg?.ifBlank { null }
    }
}

