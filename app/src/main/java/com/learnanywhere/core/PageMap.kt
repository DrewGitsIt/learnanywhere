package com.learnanywhere.core

import com.learnanywhere.data.PdfText

/**
 * Maps a passage of a document's stored text back to the PDF page it came from
 * (DESIGN §6.5 — figures ride along: the reply's cited page and the read-along
 * cursor both need "which page is this text on?").
 *
 * The two sides never match byte-for-byte: the stored text was normalized as
 * one document, [PdfText.extractPaged] normalizes page by page, so a word
 * hyphenated across a page break is joined in one and split in the other, and
 * whitespace differs around the seam. Matching is therefore done on a
 * canonical form — letters and digits only, lowercased — which erases exactly
 * those differences, with an index map back to the raw offset.
 *
 * Pure JVM (no Android): the whole of it is unit-tested. Cost is O(document)
 * per call, so callers map a section, not every sentence.
 */
object PageMap {

    /**
     * 1-based page where [snippet] starts in [paged], or null if it isn't there.
     *
     * A snippet spanning a page break resolves to the page it starts on.
     */
    fun pageFor(paged: PdfText.Paged, snippet: String): Int? {
        if (paged.pageOffsets.isEmpty() || paged.text.isEmpty()) return null
        val needle = canonicalText(snippet)
        if (needle.length < MIN_PROBE) return null
        val hay = canonicalize(paged.text)

        // Long probe first. The short ones are the fallback for drift *inside*
        // the probe window (a running head pulled into the page text, a ligature
        // the two passes resolved differently) — not a licence to match prose
        // the snippet doesn't start with, hence the floor at MIN_PROBE.
        var tried: String? = null
        for (len in PROBE_LENGTHS) {
            val probe = needle.take(len)
            if (probe == tried) continue          // needle shorter than this probe
            tried = probe
            val at = hay.text.indexOf(probe)
            if (at >= 0) return pageAt(paged.pageOffsets, hay.rawAt[at])
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

    /** Last page whose start offset is at or before [rawIndex], 1-based. */
    private fun pageAt(offsets: List<Int>, rawIndex: Int): Int {
        var lo = 0
        var hi = offsets.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] <= rawIndex) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found + 1
    }

    /** ~12 words of canonical text — distinctive enough to land on one page. */
    private val PROBE_LENGTHS = intArrayOf(72, 40, 24, 12)

    /** Below this, a "match" is a coincidence rather than a location. */
    private const val MIN_PROBE = 12
}
