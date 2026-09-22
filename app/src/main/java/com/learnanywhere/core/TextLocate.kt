package com.learnanywhere.core

/**
 * Finds a model-supplied "verbatim" quote in a document's stored text
 * (DESIGN §7.2 boilerplate boundaries, §7.3 voice seek).
 *
 * Verbatim is a polite fiction: a model re-typing a sentence normalizes
 * quotes and dashes, drops a stray ligature, loses the hyphen a PDF wrapped a
 * word on. So matching is done on a canonical form — letters and digits only,
 * lowercased — with an index map back to the raw offset, exactly the trick
 * [PageMap] uses to reconcile two normalizations of the same PDF. The
 * canonical form erases case, punctuation and whitespace, which is precisely
 * where the drift is.
 *
 * Probing shrinks: a long prefix first, then shorter ones, because drift
 * inside the quote should cost recall, not correctness. The floor at
 * [MIN_PROBE] canonical chars (~2-3 words) is what keeps a short probe from
 * landing on a coincidence.
 *
 * Pure JVM, O(document) per call — locate a section or a region boundary, not
 * every sentence.
 */
object TextLocate {

    /** Raw offset in [haystack] where [quote] starts, or null if not found. */
    fun find(haystack: String, quote: String): Int? = findRange(haystack, quote)?.first

    /**
     * The raw span of [quote] in [haystack], or null.
     *
     * The match is anchored by a prefix probe; the end is then taken by
     * walking the quote's remaining canonical length forward through the
     * haystack. That is an estimate — the tail may have drifted — but it is
     * the right one for a skip range, whose end only has to land inside the
     * boilerplate rather than on a specific character.
     */
    fun findRange(haystack: String, quote: String): IntRange? {
        if (haystack.isEmpty()) return null
        val needle = canonicalText(quote)
        if (needle.length < MIN_PROBE) return null
        val hay = canonicalize(haystack)
        if (hay.text.length < MIN_PROBE) return null

        var tried: String? = null
        for (len in PROBE_LENGTHS) {
            val probe = needle.take(len)
            if (probe == tried) continue          // needle shorter than this probe
            tried = probe
            val at = hay.text.indexOf(probe)
            if (at < 0) continue
            val endCanon = minOf(at + needle.length, hay.text.length) - 1
            return hay.rawAt[at]..hay.rawAt[endCanon]
        }
        return null
    }

    // ------------------------------------------------------------------

    /** Canonical chars plus, per char, its index in the source string. */
    private class Canon(val text: String, val rawAt: IntArray)

    private fun canonicalize(s: String): Canon {
        val chars = CharArray(s.length)
        val rawAt = IntArray(s.length)
        var n = 0
        for (i in s.indices) {
            val c = s[i]
            if (c.isLetterOrDigit()) {
                chars[n] = c.lowercaseChar()
                rawAt[n] = i
                n++
            }
        }
        return Canon(String(chars, 0, n), rawAt.copyOf(n))
    }

    private fun canonicalText(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        return sb.toString()
    }

    /** ~12 words of canonical text, then progressively less of the quote. */
    private val PROBE_LENGTHS = intArrayOf(72, 40, 24, 12)

    /** Below this, a "match" is a coincidence rather than a location. */
    private const val MIN_PROBE = 12
}
