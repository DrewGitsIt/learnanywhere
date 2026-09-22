package com.learnanywhere.core

/**
 * Regex heuristics for the matter a reader should not have to hear: arXiv
 * front-matter stamps, copyright/licence lines, the references tail,
 * acknowledgments (DESIGN §7.2).
 *
 * These are the OFFLINE floor of the skip-map — they run with no network, no
 * quota and no latency — and they also sanity-bound the LLM classifier, which
 * can hallucinate a boundary quote that swallows the paper. Everything here is
 * deliberately conservative: a false positive silences real content, while a
 * false negative merely reads a copyright line aloud. When a pattern is
 * ambiguous, it skips nothing.
 *
 * Ranges are INCLUSIVE char offsets into the raw stored text, which is never
 * rewritten (§5 prompt-cache alignment) — a skip-map is a sidecar, not an edit.
 */
object Boilerplate {

    /** Sorted, merged, non-overlapping skip ranges for [text]. */
    fun heuristicRanges(text: String): List<IntRange> {
        if (text.isBlank()) return emptyList()
        val n = text.length
        val found = ArrayList<IntRange>()
        val lines = lines(text)

        // (a) arXiv stamps and venue/preprint banners, near the top only.
        //     Bounded by length: when PDF extraction folded the stamp into the
        //     title line, dropping the line would drop the title.
        val headEnd = minOf(n, HEAD_CHARS)
        for (l in lines) {
            if (l.start >= headEnd) break
            val s = text.substring(l.start, l.end)
            if (s.length <= FRONT_MATTER_MAX_LINE &&
                (ARXIV_ID.containsMatchIn(s) || FRONT_MATTER.containsMatchIn(s))
            ) found.add(l.start..(l.end - 1))
        }

        // (b) copyright / licence / rights lines, anywhere.
        for (l in lines) {
            val s = text.substring(l.start, l.end)
            if (s.length <= RIGHTS_MAX_LINE && RIGHTS.containsMatchIn(s)) {
                found.add(l.start..(l.end - 1))
            }
        }

        // (c) References/Bibliography heading through the end — but only when
        //     it sits in the tail, so a body sentence naming "the references"
        //     of a method can't truncate the document.
        val tailFrom = (n * TAIL_FRACTION).toInt()
        for (l in lines) {
            if (l.start < tailFrom) continue
            val s = text.substring(l.start, l.end)
            if (REFS_HEADING.containsMatchIn(s) || REFS_JOINED.containsMatchIn(s)) {
                found.add(l.start..(n - 1))
                break
            }
        }

        // (d) Acknowledgments: the heading plus its (short) body.
        ackRange(text, lines)?.let { found.add(it) }

        return Ranges.normalize(found, n)
    }

    /**
     * [text] with [skip] removed, the holes closed up with paragraph breaks —
     * what plain audiobook playback reads aloud. Returns [text] unchanged when
     * nothing is skipped, so the un-classified path stays byte-identical.
     */
    fun keptText(text: String, skip: List<IntRange>): String {
        val holes = Ranges.normalize(skip, text.length)
        if (holes.isEmpty()) return text
        val parts = ArrayList<String>(holes.size + 1)
        var at = 0
        for (h in holes) {
            if (h.first > at) parts.add(text.substring(at, h.first).trim())
            at = h.last + 1
        }
        if (at < text.length) parts.add(text.substring(at).trim())
        return parts.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    // ------------------------------------------------------------------

    private class Line(val start: Int, val end: Int)   // end exclusive, no '\n'

    private fun lines(text: String): List<Line> {
        val out = ArrayList<Line>()
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n') { out.add(Line(start, i)); start = i + 1 }
        }
        out.add(Line(start, text.length))
        return out.filter { it.end > it.start }
    }

    /**
     * The acknowledgments block, or null.
     *
     * Two shapes, because [com.learnanywhere.data.PdfText.normalize] turns
     * single newlines into spaces: the heading survives as its own paragraph
     * (body is the NEXT paragraph) or it was glued to its first sentence (body
     * is the rest of THIS paragraph). Either way the block must be short —
     * anything long is a mis-detection, and mis-detections here delete prose.
     */
    private fun ackRange(text: String, lines: List<Line>): IntRange? {
        for (l in lines) {
            val s = text.substring(l.start, l.end)
            val alone = ACK_HEADING.containsMatchIn(s)
            if (!alone && !ACK_JOINED.containsMatchIn(s)) continue
            var end = paragraphEnd(text, l.start)
            if (alone && end <= l.end) end = paragraphEnd(text, paragraphStart(text, end))
            if (end - l.start > ACK_MAX) return null
            return l.start..(end - 1)
        }
        return null
    }

    /** Exclusive end of the paragraph containing [from] (before its blank line). */
    private fun paragraphEnd(text: String, from: Int): Int {
        val at = text.indexOf("\n\n", from)
        return if (at < 0) text.length else at
    }

    /** Start of the paragraph after the blank line at [afterEnd]. */
    private fun paragraphStart(text: String, afterEnd: Int): Int {
        var i = afterEnd
        while (i < text.length && text[i] == '\n') i++
        return i
    }

    // ------------------------------------------------------------------

    /** Front matter is only believable near the top of the document. */
    private const val HEAD_CHARS = 3000
    private const val FRONT_MATTER_MAX_LINE = 140
    private const val RIGHTS_MAX_LINE = 300
    private const val ACK_MAX = 2000

    /** A references heading before this point in the doc is a body mention. */
    private const val TAIL_FRACTION = 0.6

    private val ARXIV_ID = Regex("""arXiv:\s?\d{4}\.\d{4,5}(v\d+)?""", RegexOption.IGNORE_CASE)

    private val FRONT_MATTER = Regex(
        """^\s*(preprint\b|under review\b|(published|accepted) as a (conference|workshop) paper\b|""" +
        """submitted to\b|to appear in\b|accepted (at|to|for publication)\b)""",
        RegexOption.IGNORE_CASE
    )

    private val RIGHTS = Regex(
        """(©|\(c\)\s*\d{4}|copyright\s*(\(c\)|©)?\s*\d{4}|all rights reserved|""" +
        """this (work|article|paper|document) is licensed|licen[cs]ed under|""" +
        """creative commons|\bcc[ -]by\b|permission to make digital or hard copies)""",
        RegexOption.IGNORE_CASE
    )

    private val REFS_HEADING = Regex(
        """^[ \t]*(\d{1,2}[.)]?[ \t]+|[ivx]{1,5}[.)][ \t]+)?""" +
        """(references|bibliography|works cited|literature cited)[ \t]*:?[ \t]*$""",
        RegexOption.IGNORE_CASE
    )

    /** Heading whose list got glued on by line re-flow: "References [1] Smith…". */
    private val REFS_JOINED = Regex(
        """^[ \t]*(\d{1,2}[.)]?[ \t]+)?(references|bibliography)[ \t]+(\[\d+\]|\[[A-Z])""",
        RegexOption.IGNORE_CASE
    )

    private val ACK_HEADING = Regex(
        """^[ \t]*(\d{1,2}[.)]?[ \t]+)?(acknowledge?ments?)[ \t]*:?[ \t]*$""",
        RegexOption.IGNORE_CASE
    )

    private val ACK_JOINED = Regex(
        """^[ \t]*(\d{1,2}[.)]?[ \t]+)?(acknowledge?ments?)[ \t]+[A-Z]""",
        RegexOption.IGNORE_CASE
    )
}
