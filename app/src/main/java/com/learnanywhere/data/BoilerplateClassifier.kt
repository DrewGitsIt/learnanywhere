package com.learnanywhere.data

import com.learnanywhere.core.Boilerplate
import com.learnanywhere.core.Ranges
import com.learnanywhere.core.TextLocate

/**
 * Builds a document's skip-map: the char ranges audiobook playback and
 * read-with-me pass over silently (DESIGN §7.2).
 *
 * One LLM call at add-time labels skippable regions by verbatim BOUNDARY
 * QUOTES rather than offsets — a model cannot count characters, but it can
 * copy a sentence, and [TextLocate] turns a copied sentence back into an
 * offset. The reply is advisory: the result is unioned with
 * [Boilerplate.heuristicRanges], which is both the floor (offline, quota-free,
 * always runs) and the sanity bound (a hallucinated quote pair that would
 * swallow the paper is dropped, not obeyed).
 *
 * [llm] is a plain lambda — Gemini's `generateText` wrapped by the caller — so
 * this class stays JVM-testable and knows nothing about transports or keys.
 * Null means heuristics only, which is also what any failure degrades to:
 * **classify never throws**, because a classifier crash must not cost the user
 * their document.
 */
class BoilerplateClassifier(
    private val llm: (suspend (prompt: String) -> String?)? = null
) {

    suspend fun classify(text: String): List<IntRange> =
        classifyOrNull(text) ?: safeHeuristics(text)

    /**
     * Like [classify], but null when the LLM pass did not actually run (no
     * lambda, null reply, or an exception) — so the caller can fall back to
     * heuristics WITHOUT persisting them, and a later cold start retries the
     * LLM pass. A non-null result always reflects a completed LLM run (or a
     * blank document, where there is nothing a retry could add).
     */
    suspend fun classifyOrNull(text: String): List<IntRange>? {
        val heuristics = safeHeuristics(text)
        val ask = llm ?: return null
        if (text.isBlank()) return heuristics
        return try {
            val reply = ask(prompt(text)) ?: return null
            val located = parse(reply).mapNotNull { locate(text, it) }
            val merged = Ranges.normalize(heuristics + located, text.length)
            // Last line of defence: if honouring the model would mute most of
            // the document, the model is wrong about what "boilerplate" is.
            if (Ranges.span(merged) > text.length * MAX_TOTAL_FRACTION) heuristics else merged
        } catch (t: Throwable) {
            null
        }
    }

    private fun safeHeuristics(text: String): List<IntRange> = try {
        Boilerplate.heuristicRanges(text)
    } catch (t: Throwable) {
        emptyList()
    }

    // ------------------------------------------------------------------

    private class Region(val startQuote: String, val endQuote: String)

    /**
     * Head and tail excerpts only: boilerplate lives at the edges, and the
     * whole point of this call is that it must be cheap enough to run on every
     * added document on a free-tier key.
     */
    private fun prompt(text: String): String {
        val head: String
        val tail: String
        if (text.length <= HEAD_CHARS + TAIL_CHARS) {
            head = text
            tail = ""
        } else {
            head = text.substring(0, HEAD_CHARS)
            tail = text.substring(text.length - TAIL_CHARS)
        }
        val sb = StringBuilder()
        sb.append(
            "You are preparing a document to be READ ALOUD. Identify only the regions a " +
            "listener should NOT hear: arXiv front-matter and venue banners, copyright or " +
            "licence blocks, the references/bibliography list, acknowledgments, and long " +
            "citation footnotes. Body text — abstract, introduction, methods, results, " +
            "conclusions, and any narrative prose — is NEVER boilerplate.\n\n" +
            "Reply with ONLY a JSON array, no prose and no code fences. Each element is " +
            "{\"start_quote\": \"...\", \"end_quote\": \"...\"} where both quotes are copied " +
            "VERBATIM from the excerpts below: start_quote is the first few words of the " +
            "region, end_quote the last few words. Use at least six words per quote. " +
            "If nothing should be skipped, reply [].\n\n"
        )
        sb.append("--- DOCUMENT HEAD ---\n").append(head).append('\n')
        if (tail.isNotEmpty()) sb.append("\n--- DOCUMENT TAIL (end of document) ---\n").append(tail).append('\n')
        return sb.toString()
    }

    /** Tolerant JSON extraction: models wrap arrays in fences and commentary. */
    private fun parse(reply: String): List<Region> {
        val from = reply.indexOf('[')
        val to = reply.lastIndexOf(']')
        if (from < 0 || to <= from) return emptyList()
        val arr = org.json.JSONArray(reply.substring(from, to + 1))
        val out = ArrayList<Region>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optString("start_quote").trim()
            val e = o.optString("end_quote").trim()
            if (s.isNotEmpty() && e.isNotEmpty()) out.add(Region(s, e))
        }
        return out
    }

    /** A quote pair as a raw range, or null when it doesn't survive scrutiny. */
    private fun locate(text: String, r: Region): IntRange? {
        val start = TextLocate.findRange(text, r.startQuote) ?: return null
        val end = TextLocate.findRange(text, r.endQuote) ?: return null
        if (end.last < start.first) return null                       // inverted
        val range = start.first..end.last
        val size = range.last - range.first + 1
        if (size > text.length * MAX_REGION_FRACTION) return null     // swallows the doc
        return range
    }

    private companion object {
        // ~12k chars of document text in the prompt, per DESIGN §7.2's
        // "one cheap call at add-time".
        const val HEAD_CHARS = 6000
        const val TAIL_CHARS = 6000

        /** No single labelled region may cover more than half the document. */
        const val MAX_REGION_FRACTION = 0.5

        /** Nor may the whole skip-map, once merged with the heuristics. */
        const val MAX_TOTAL_FRACTION = 0.7
    }
}
