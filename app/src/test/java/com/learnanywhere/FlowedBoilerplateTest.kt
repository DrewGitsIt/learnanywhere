package com.learnanywhere

import com.learnanywhere.core.Boilerplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boilerplate heuristics on FLOWED text (DESIGN §7.2): PdfText.extract
 * re-flows a PDF into one newline-free line (observed live 2026-09-22 — the
 * stored "Attention" text is 39k chars with zero '\n'), so these patterns must
 * work with no line structure at all.
 */
class FlowedBoilerplateTest {

    private fun covered(ranges: List<IntRange>, i: Int) = ranges.any { i in it }

    /** Newline-free prose with no citation marks or years. */
    private fun prose(chars: Int): String {
        val unit = "The quick brown fox studies transformers and attention in depth without citing anyone here. "
        val sb = StringBuilder()
        while (sb.length < chars) sb.append(unit)
        // Cut on a space, not mid-word: gluing "…anyone heReferences…" would
        // erase the \b the heuristics (correctly) require.
        return sb.substring(0, chars - 1) + " "
    }

    /** Bracket-style citations, ~100 chars each, dense in years and brackets. */
    private fun bracketRefs(entries: Int): String {
        val sb = StringBuilder()
        for (i in 1..entries)
            sb.append("[").append(i).append("] Alice Smith and Bob Jones. A careful study of things, volume ")
                .append(i).append(". Journal of Tests, 2016. ")
        return sb.toString()
    }

    /** Author-year (ACL) style citations: no brackets, dense in years. */
    private fun authorYearRefs(entries: Int): String {
        val sb = StringBuilder()
        for (i in 1..entries)
            sb.append("Alice Smith, Bob Jones, and Carol Lee. 2018. A careful study of things. In Proceedings of Tests. ")
        return sb.toString()
    }

    private val grant = "Provided proper attribution is provided, BigCo hereby grants permission " +
            "to reproduce the tables and figures in this paper solely for scholarly works. "

    @Test
    fun attentionShapedDocSkipsGrantAckAndReferences() {
        val body = prose(8000)
        val ack = "Acknowledgements We are grateful to our colleagues for fruitful comments and inspiration. "
        val refs = "References " + bracketRefs(30)
        val text = grant + body + ack + refs
        val ranges = Boilerplate.heuristicRanges(text)

        // Head grant: skipped from 0 through its sentence end; title area after it kept.
        assertTrue(covered(ranges, 0))
        assertTrue(covered(ranges, grant.length - 5))
        assertFalse(covered(ranges, grant.length + 50))

        // Acknowledgments through the references, to the end (citations run out the doc).
        val ackAt = text.indexOf("Acknowledgements")
        val refsAt = text.indexOf("References [1]")
        assertTrue(covered(ranges, ackAt))
        assertTrue(covered(ranges, refsAt))
        assertTrue(covered(ranges, text.length - 1))

        // The body is untouched.
        assertFalse(covered(ranges, grant.length + body.length / 2))
    }

    @Test
    fun authorYearReferencesAreSkipped() {
        val text = prose(8000) + "References " + authorYearRefs(30)
        val ranges = Boilerplate.heuristicRanges(text)
        val refsAt = text.indexOf("References Alice")
        assertTrue(covered(ranges, refsAt))
        assertTrue(covered(ranges, text.length - 1))
        assertFalse(covered(ranges, 4000))
    }

    @Test
    fun appendixAfterReferencesIsKept() {
        // BERT's shape: a dense reference list, then a mark-free appendix.
        val refs = "References " + bracketRefs(30)          // ~3k chars, dense
        val appendix = prose(2500)                           // no years, no brackets
        val text = prose(8000) + refs + appendix
        val ranges = Boilerplate.heuristicRanges(text)
        assertTrue(covered(ranges, text.indexOf("References [1]")))
        // The walk may overshoot by at most one window (1000) into the
        // appendix; its tail must survive.
        assertFalse(covered(ranges, text.length - 1000))
        assertFalse(covered(ranges, text.length - 1))
    }

    @Test
    fun earlyReferencesMentionIsNotATail() {
        // "References [1]" in the first half is never the list.
        val text = prose(1000) + "References [1] someone said so. " + prose(8000)
        assertEquals(emptyList<IntRange>(), Boilerplate.heuristicRanges(text))
    }

    @Test
    fun sparseReferencesCandidateSkipsNothing() {
        // Capitalized "References" met by prose, not a citation list: the
        // density gate must reject it even in the tail.
        val text = prose(8000) + "References Are discussed above by the author at length. " + prose(400)
        assertEquals(emptyList<IntRange>(), Boilerplate.heuristicRanges(text))
    }

    @Test
    fun ackWithoutReferencesListIsKept() {
        // Acknowledgments only skip when anchored to a found reference list.
        val text = prose(6000) + "Acknowledgements We thank everyone warmly. " + prose(2000)
        assertEquals(emptyList<IntRange>(), Boilerplate.heuristicRanges(text))
    }

    @Test
    fun midDocLicenseMentionIsKept() {
        // "licensed under" deep in the body must not trigger the head-grant pass.
        val text = prose(3000) + "The dataset is licensed under a permissive licence. " + prose(3000)
        assertEquals(emptyList<IntRange>(), Boilerplate.heuristicRanges(text))
    }

    @Test
    fun plainFlowedProseIsUntouched() {
        assertEquals(emptyList<IntRange>(), Boilerplate.heuristicRanges(prose(10000)))
    }
}
