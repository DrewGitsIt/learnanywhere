package com.learnanywhere.data

import java.io.File

/**
 * Sidecar persistence for skip-maps (DESIGN §7.2).
 *
 * A sidecar, not a column, for the reason the whole feature exists: the stored
 * document text must stay byte-identical so the grounding prefix keeps hitting
 * Gemini's implicit cache (§5). The skip-map is therefore derived data that
 * lives beside the document — one small JSON file per doc id, cheap to write
 * at add-time and cheap to throw away if the classifier improves.
 *
 * Ranges are inclusive `[start, end]` pairs into the raw text.
 *
 * Everything here is forgiving: a missing or unreadable file is "never
 * computed" (null), which simply re-runs the classifier. Nothing throws.
 */
class SkipStore(private val dir: File) {

    /**
     * Saved ranges for [docId], or null when none were ever computed.
     *
     * An EMPTY list is a real answer — "classified, nothing to skip" — and is
     * distinct from null, or a clean document would be re-classified forever.
     */
    fun load(docId: String): List<IntRange>? = try {
        val f = fileFor(docId)
        if (!f.isFile) null else {
            val root = org.json.JSONObject(f.readText())
            val arr = root.getJSONArray("ranges")
            val out = ArrayList<IntRange>(arr.length())
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                val a = pair.getInt(0)
                val b = pair.getInt(1)
                if (a in 0..b) out.add(a..b)
            }
            out
        }
    } catch (t: Throwable) {
        null                    // truncated write, older schema, junk on disk
    }

    fun save(docId: String, ranges: List<IntRange>) {
        try {
            dir.mkdirs()
            val arr = org.json.JSONArray()
            for (r in ranges) arr.put(org.json.JSONArray().put(r.first).put(r.last))
            val root = org.json.JSONObject().put("v", VERSION).put("ranges", arr)
            fileFor(docId).writeText(root.toString())
        } catch (t: Throwable) {
            // Derived data: failing to cache it costs one re-classification.
        }
    }

    fun remove(docId: String) {
        try { fileFor(docId).delete() } catch (t: Throwable) { }
    }

    // ------------------------------------------------------------------

    /**
     * Doc ids are UUIDs today, but they have been URLs before now, so the
     * filename is sanitized AND disambiguated by a hash of the original — two
     * ids that sanitize alike must not share a sidecar.
     */
    private fun fileFor(docId: String): File {
        val safe = docId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(48)
        return File(dir, "skip-$safe-${docId.hashCode().toUInt().toString(16)}.json")
    }

    private companion object {
        const val VERSION = 1
    }
}
