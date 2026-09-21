package com.learnanywhere.core

/**
 * Pure helpers for the tool-loop wire format (audit 2026-09-21).
 *
 * These exist because the loop splices three kinds of untrusted text into
 * request JSON: tool names/ids from the MODEL, and tool results built from
 * THIRD-PARTY responses (a Tavily HTML error page once carried newlines that
 * would have 400'd the whole turn). Everything here goes through org.json —
 * real on Android and a real test dependency on the JVM.
 */
object ToolWire {

    /**
     * Build one functionResponse part. [resultJson] must be a JSON object;
     * anything that doesn't parse (a tool bug) is replaced by a well-formed
     * error the model can act on, because one malformed splice kills the
     * entire turn with an INVALID_ARGUMENT 400.
     */
    fun functionResponsePart(id: String?, name: String, resultJson: String): String {
        val response = try {
            org.json.JSONObject(resultJson)
        } catch (t: Throwable) {
            org.json.JSONObject().put("error", "tool returned malformed output")
        }
        val fr = org.json.JSONObject()
        if (id != null) fr.put("id", id)
        fr.put("name", name)
        fr.put("response", response)
        return org.json.JSONObject().put("functionResponse", fr).toString()
    }

    /**
     * Merge the text fragments a streamed reply accumulates (one rawPart per
     * SSE chunk) back into the part list the model actually produced: one
     * aggregate text part, other parts (functionCall, …) kept in order.
     *
     * Gemini 3 requires the model's parts echoed VERBATIM in the tool loop;
     * echoing thirty single-fragment text parts is a reconstruction the model
     * never emitted. The merged part carries the LAST thoughtSignature seen
     * on any merged fragment (signatures ride the final chunk of a span) and
     * sits at the position of the first text fragment.
     */
    fun coalesceTextParts(rawParts: List<String>): List<String> {
        if (rawParts.size <= 1) return rawParts
        val parsed = rawParts.map { raw ->
            try { org.json.JSONObject(raw) } catch (t: Throwable) { null }
        }
        // A part is a mergeable text fragment iff its only content is text
        // (plus optional thoughtSignature) — never a functionCall or thought.
        fun isTextFragment(o: org.json.JSONObject?): Boolean =
            o != null && o.has("text") && !o.optBoolean("thought", false) &&
                    o.keys().asSequence().all { k -> k == "text" || k == "thoughtSignature" }

        if (parsed.count { isTextFragment(it) } <= 1) return rawParts

        val out = ArrayList<String>()
        val textBuf = StringBuilder()
        var signature: String? = null
        var textEmitted = false

        fun emitTextIfPending() {
            if (textBuf.isEmpty() && signature == null) return
            if (textEmitted) return
            val o = org.json.JSONObject().put("text", textBuf.toString())
            signature?.let { o.put("thoughtSignature", it) }
            out.add(o.toString())
            textEmitted = true
        }

        parsed.forEachIndexed { i, o ->
            if (isTextFragment(o)) {
                textBuf.append(o!!.optString("text"))
                o.optString("thoughtSignature").ifEmpty { null }?.let { signature = it }
            } else {
                // First non-text part after fragments: flush the aggregate
                // ahead of it so text precedes functionCall like the model's
                // own non-streamed part order.
                emitTextIfPending()
                out.add(rawParts[i])
            }
        }
        emitTextIfPending()
        return out
    }

    /**
     * Hostname-pattern check for URLs the MODEL asks us to fetch: loopback,
     * RFC-1918, link-local and mDNS names are never legitimate documents.
     * String-based on purpose (no DNS) — this guards against prompt-injected
     * URLs, not a determined attacker on the local network.
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost") ||
            h.endsWith(".local") || h.endsWith(".internal") ||
            h == "0.0.0.0") return true
        if (h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
        val octets = h.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size == 4 && octets.all { it in 0..255 }) {
            return octets[0] == 127 || octets[0] == 10 ||
                    (octets[0] == 192 && octets[1] == 168) ||
                    (octets[0] == 172 && octets[1] in 16..31) ||
                    (octets[0] == 169 && octets[1] == 254)
        }
        return false
    }
}
