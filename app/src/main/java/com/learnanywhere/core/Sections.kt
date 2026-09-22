package com.learnanywhere.core

/**
 * Pure sectionizer for "read with me" mode: splits a document's text into
 * speakable sections (~[target] chars), preferring paragraph boundaries and
 * falling back to sentence cuts inside giant paragraphs. Unit-tested.
 *
 * Sections carry their start offset in the SOURCE text (DESIGN §7.4): the
 * skip-map and voice seek both need "where in the stored text is this?", and
 * they must share one geometry or a jump lands in the wrong place. The stored
 * text is never rewritten (§5 cache alignment), so a skip-map is applied here,
 * as a filter over offsets, rather than by editing the document.
 */
object Sections {

    /** A speakable chunk plus the offset of its first char in the source text. */
    data class Section(val text: String, val start: Int)

    fun split(text: String, target: Int = 1200): List<String> =
        splitWithOffsets(text, target).map { it.text }

    /**
     * Same sectioning as [split], but skipping the (inclusive) char ranges in
     * [skip] — boilerplate the reader should not hear (DESIGN §7.2).
     *
     * A hole acts as a paragraph boundary: text either side of skipped matter
     * is never glued into one sentence run. With an empty [skip] the section
     * texts are exactly [split]'s output.
     */
    fun splitWithOffsets(
        text: String,
        target: Int = 1200,
        skip: List<IntRange> = emptyList()
    ): List<Section> {
        val paras = ArrayList<Piece>()
        for ((from, to) in keptSpans(text.length, skip)) {
            paragraphs(text, from, to, paras)
        }

        val out = ArrayList<Section>()
        val sb = StringBuilder()
        var sbStart = 0
        fun flush() {
            if (sb.isNotEmpty()) { out.add(Section(sb.toString(), sbStart)); sb.setLength(0) }
        }

        for (p0 in paras) {
            var p = p0
            // A single paragraph far over target: cut at sentence boundaries.
            while (p.text.length > target * 2) {
                flush()
                val cut = sentenceCut(p.text, target)
                val head = trimmed(p.text, 0, cut, p.start)
                out.add(Section(head.text, head.start))
                p = trimmed(p.text, cut, p.text.length, p.start)
            }
            when {
                sb.isEmpty() -> { sb.append(p.text); sbStart = p.start }
                sb.length < target -> sb.append("\n\n").append(p.text)
                else -> { flush(); sb.append(p.text); sbStart = p.start }
            }
        }
        flush()
        return out
    }

    // ------------------------------------------------------------------

    /** A stretch of source text plus where it starts in that source. */
    private class Piece(val text: String, val start: Int)

    /**
     * The complement of [skip] within `0 until length`, as (from, toExclusive)
     * pairs. Ranges may arrive unsorted, overlapping, or out of bounds.
     */
    private fun keptSpans(length: Int, skip: List<IntRange>): List<Pair<Int, Int>> {
        if (length == 0) return emptyList()
        if (skip.isEmpty()) return listOf(0 to length)
        val holes = Ranges.normalize(skip, length)
        if (holes.isEmpty()) return listOf(0 to length)
        val out = ArrayList<Pair<Int, Int>>(holes.size + 1)
        var at = 0
        for (h in holes) {
            if (h.first > at) out.add(at to h.first)
            at = h.last + 1
        }
        if (at < length) out.add(at to length)
        return out
    }

    /** Paragraphs of `text[from, to)`, trimmed and non-empty, appended to [into]. */
    private fun paragraphs(text: String, from: Int, to: Int, into: MutableList<Piece>) {
        var last = from
        var i = from
        while (i < to) {
            if (text[i] == '\n') {
                var end = i + 1
                while (end < to && text[end] == '\n') end++
                if (end - i >= 2) {                     // a blank line: paragraph break
                    val p = trimmed(text, last, i, 0)
                    if (p.text.isNotEmpty()) into.add(p)
                    last = end
                    i = end
                    continue
                }
            }
            i++
        }
        val p = trimmed(text, last, to, 0)
        if (p.text.isNotEmpty()) into.add(p)
    }

    /**
     * `text[from, to)` with surrounding whitespace dropped, carrying the offset
     * of its first surviving char (base [base] plus the index within [text]).
     */
    private fun trimmed(text: String, from: Int, to: Int, base: Int): Piece {
        var b = from
        var e = to
        while (b < e && text[b].isWhitespace()) b++
        while (e > b && text[e - 1].isWhitespace()) e--
        return Piece(text.substring(b, e), base + b)
    }

    /** Last sentence end at or before [target], else hard cut at target. */
    private fun sentenceCut(s: String, target: Int): Int {
        var best = -1
        for (i in 0 until minOf(s.length - 1, target)) {
            val c = s[i]
            if ((c == '.' || c == '!' || c == '?') && s[i + 1].isWhitespace()) best = i + 1
        }
        return if (best > 0) best else minOf(target, s.length)
    }
}
