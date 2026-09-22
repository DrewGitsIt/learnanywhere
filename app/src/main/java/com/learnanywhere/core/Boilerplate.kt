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

        // (e)–(g) Flowed-text passes: PdfText.extract re-flows a PDF into ONE
        // newline-free line (observed live 2026-09-22, "Attention" = 39k chars,
        // zero '\n'), so every line-anchored pattern above is structurally
        // blind on the app's primary content type. These patterns carry their
        // own anchors instead of borrowing the line's.
        flowedRanges(text, found)

        return Ranges.normalize(found, n)
    }

    /**
     * Skip ranges that need no line structure. Same conservatism contract:
     * each pattern must be unmistakable on its own, because there is no
     * "short line" bound left to lean on.
     */
    private fun flowedRanges(text: String, found: MutableList<IntRange>) {
        val n = text.length

        // (e) The references list. Start: the heading glued to its first
        //     entry — "References [1] …" (bracket style) or "References Kevin
        //     Clark, … 2018." (author-year style) — in the tail half only,
        //     last match wins. End: a citation-density walk, because papers
        //     put appendices AFTER the references (BERT's runs 12k chars and
        //     is followed by a real appendix) — windows keep skipping while
        //     they stay dense with year/bracket citation marks. A candidate
        //     whose FIRST window is sparse is prose that merely said
        //     "References", and skips nothing.
        val tailFrom = (n * FLOWED_TAIL_FRACTION).toInt()
        val refs = REFS_FLOWED.findAll(text).lastOrNull { it.range.first >= tailFrom }
        if (refs != null) {
            val end = citationWalk(text, refs.range.first)
            if (end > refs.range.first) {
                found.add(refs.range.first..(end - 1))

                // (g) Acknowledgments directly ahead of the references list:
                //     only believable when anchored to a found list, which is
                //     what keeps a body mention from deleting prose.
                val ackFrom = maxOf(0, refs.range.first - ACK_BEFORE_REFS)
                ACK_JOINED_ANYWHERE.find(text, ackFrom)?.let { ack ->
                    if (ack.range.first < refs.range.first) {
                        found.add(ack.range.first..(refs.range.first - 1))
                    }
                }
            }
        }

        // (f) A rights/permission grant opening the document ("Provided
        //     proper attribution is provided, Google hereby grants…"): skip
        //     from char 0 through the end of the sentence holding the match.
        //     Position-bounded to the very head and sentence-bounded on the
        //     right; if no sentence boundary shows up nearby, skip nothing.
        //     FLOWED heads only (no newline in the first GRANT_HEAD_CHARS):
        //     a structured head keeps its title on a line above the rights
        //     line, and skipping "0..sentence end" there deletes the title —
        //     the line-based RIGHTS pass already covers that shape.
        if (text.take(GRANT_HEAD_CHARS).contains('\n')) return
        val grant = RIGHTS_FLOWED.find(text)
        if (grant != null && grant.range.first < GRANT_HEAD_CHARS) {
            val dot = text.indexOf(". ", grant.range.last)
            if (dot in grant.range.last until GRANT_MAX_END) found.add(0..dot)
        }
    }

    /**
     * Exclusive end of the citation-dense region starting at [from]: advance
     * window by window while each holds at least [CITATION_MIN_MARKS] year
     * ("2018.", "1997a") or bracket ("[12]") citation marks. Returns [from]
     * itself when the very first window is sparse — i.e. "not a list at all".
     */
    private fun citationWalk(text: String, from: Int): Int {
        var at = from
        while (at < text.length) {
            val w = text.substring(at, minOf(text.length, at + CITATION_WINDOW))
            val marks = CITATION_YEAR.findAll(w).count() + CITATION_BRACKET.findAll(w).count()
            if (marks < CITATION_MIN_MARKS) break
            at += CITATION_WINDOW
        }
        return minOf(at, text.length)
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

    // ---- flowed-text (newline-free) patterns; see flowedRanges ----

    /** How far the acknowledgments block may sit ahead of the references. */
    private const val ACK_BEFORE_REFS = 3000
    /** A rights grant is only believable at the very top of the document. */
    private const val GRANT_HEAD_CHARS = 600
    /** …and its closing sentence boundary must show up soon after. */
    private const val GRANT_MAX_END = 900

    /** References list start only believable in the back half of the doc. */
    private const val FLOWED_TAIL_FRACTION = 0.5

    /** Citation-density walk that finds where a reference list ends. */
    private const val CITATION_WINDOW = 1000
    private const val CITATION_MIN_MARKS = 2
    private val CITATION_YEAR = Regex("""\b(19|20)\d{2}[a-z]?\b""")
    private val CITATION_BRACKET = Regex("""\[\d{1,3}\]""")

    /**
     * A flowed reference list's opening: the heading glued to a bracket
     * citation ("References [1]") or to an author-year entry ("References
     * Kevin Clark, …"). Capitalized heading only — lowercase is prose.
     */
    private val REFS_FLOWED = Regex(
        """\b(References|Bibliography|REFERENCES)\s*(\[\s*1\s*\]|(?=[A-Z][a-z]))"""
    )

    /** Acknowledgments heading glued mid-flow to its first sentence. */
    private val ACK_JOINED_ANYWHERE = Regex(
        """\bAcknowledge?ments?\s+[A-Z]"""
    )

    /** Rights/permission grants that open re-flowed papers. */
    private val RIGHTS_FLOWED = Regex(
        """(hereby grants? permission|permission (is granted|to (reproduce|reprint|make digital or hard copies))|""" +
        """all rights reserved|licen[cs]ed under|creative commons)""",
        RegexOption.IGNORE_CASE
    )
}
