package com.learnanywhere

import com.learnanywhere.core.PageMap
import com.learnanywhere.data.PdfText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DESIGN §6.5 — figures ride along. PageMap is the whole testable half of it:
 * pdfbox-android can't run in a JVM test, so [PdfText.extractPaged] stays a
 * thin wrapper and every mapping decision is made here.
 *
 * The fixtures deliberately mimic the real mismatch: `paged` is normalized
 * page by page, the snippets come from a document normalized as a whole.
 */
class PageMapTest {

    // ---- fixture -----------------------------------------------------

    private val page1 = "Attention Is All You Need. The dominant sequence transduction " +
            "models are based on complex recurrent or convolutional neural networks that " +
            "include an encoder and a decoder. We propose a new simple network archi-"
    private val page2 = "tecture, the Transformer, based solely on attention mechanisms, " +
            "dispensing with recurrence and convolutions entirely. Experiments on two " +
            "machine translation tasks show these models to be superior in quality."
    private val page3 = "Table 1 lists maximum path lengths, per-layer complexity and " +
            "minimum number of sequential operations for different layer types."

    /** What extractPaged would return for the three pages above. */
    private fun paged(vararg pages: String): PdfText.Paged {
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        pages.forEachIndexed { i, p ->
            if (i > 0) sb.append("\n\n")
            offsets.add(sb.length)
            sb.append(p)
        }
        return PdfText.Paged(sb.toString(), offsets)
    }

    private val doc = paged(page1, page2, page3)

    // ---- basics ------------------------------------------------------

    @Test
    fun findsTheStartingPageOfASnippet() {
        assertEquals(1, PageMap.pageFor(doc, "The dominant sequence transduction models are based on"))
        assertEquals(2, PageMap.pageFor(doc, "dispensing with recurrence and convolutions entirely"))
        assertEquals(3, PageMap.pageFor(doc, "Table 1 lists maximum path lengths, per-layer complexity"))
    }

    @Test
    fun firstPageStartsAtOffsetZero() {
        assertEquals(1, PageMap.pageFor(doc, doc.text.take(80)))
    }

    @Test
    fun snippetNotInTheDocumentIsNull() {
        assertNull(PageMap.pageFor(doc,
            "Diffusion models beat GANs on image synthesis benchmarks across the board."))
    }

    @Test
    fun emptyOrTextlessDocumentIsNull() {
        assertNull(PageMap.pageFor(PdfText.Paged("", emptyList()), "anything at all here"))
        assertNull(PageMap.pageFor(PdfText.Paged("some text", emptyList()), "some text here now"))
    }

    // ---- the two normalizations disagree -----------------------------

    @Test
    fun snippetSpanningAPageBreakResolvesToThePageItStartsOn() {
        // Whole-document normalize() de-hyphenated "archi-\ntecture" across the
        // page break; the per-page pass could not. The snippet must still land
        // on page 1, where it begins.
        val snippet = "We propose a new simple network architecture, the Transformer, " +
                "based solely on attention mechanisms"
        assertEquals(1, PageMap.pageFor(doc, snippet))
    }

    @Test
    fun snippetStartingRightAfterAPageBreakResolvesToTheLaterPage() {
        // First canonical chars are "tecturethetransformer..." — page 2's own text.
        assertEquals(2, PageMap.pageFor(doc,
            "tecture, the Transformer, based solely on attention mechanisms, dispensing"))
    }

    @Test
    fun whitespaceAndLineBreakDriftDoesNotPreventAMatch() {
        val snippet = "  Experiments\non   two\tmachine\n\ntranslation tasks show these models  "
        assertEquals(2, PageMap.pageFor(doc, snippet))
    }

    @Test
    fun punctuationAndCaseDriftDoesNotPreventAMatch() {
        val snippet = "COMPLEX RECURRENT — OR — CONVOLUTIONAL \"NEURAL\" NETWORKS, that include an encoder"
        assertEquals(1, PageMap.pageFor(doc, snippet))
    }

    @Test
    fun driftInsideTheProbeWindowFallsBackToAShorterProbe() {
        // A running head the whole-document pass kept and the page pass dropped:
        // the first ~72 canonical chars of the snippet are not on any page, but
        // its opening words are.
        val snippet = "Table 1 lists maximum path lengths [NIPS 2017, Long Beach, CA, USA, " +
                "preprint under review] per-layer complexity and minimum number"
        assertEquals(3, PageMap.pageFor(doc, snippet))
    }

    // ---- short snippets ----------------------------------------------

    @Test
    fun shortButDistinctiveSnippetStillMaps() {
        assertEquals(3, PageMap.pageFor(doc, "sequential operations"))
        assertEquals(1, PageMap.pageFor(doc, "encoder and a decoder"))
    }

    @Test
    fun tooShortToLocateIsNull() {
        // Fewer than 12 canonical chars would match by coincidence, not location.
        assertNull(PageMap.pageFor(doc, "the"))
        assertNull(PageMap.pageFor(doc, "Table 1"))
        assertNull(PageMap.pageFor(doc, ""))
        assertNull(PageMap.pageFor(doc, "   \n  "))
    }

    // ---- page arithmetic ---------------------------------------------

    @Test
    fun everyPageIsReachableAndBoundariesAreExact() {
        val pages = (1..25).map { "Page $it body text: unique marker word zeta$it follows here." }
        val many = paged(*pages.toTypedArray())
        pages.forEachIndexed { i, p ->
            assertEquals(i + 1, PageMap.pageFor(many, p), "page ${i + 1} body")
            // First 20 chars of the page: still that page, never the one before.
            assertEquals(i + 1, PageMap.pageFor(many, p.take(20)), "page ${i + 1} head")
        }
    }

    @Test
    fun blankPagesDoNotSwallowTheFollowingPage() {
        val withBlank = paged(page1, "", page2)
        assertEquals(3, PageMap.pageFor(withBlank, "dispensing with recurrence and convolutions"))
        assertEquals(1, PageMap.pageFor(withBlank, "The dominant sequence transduction models"))
    }

    @Test
    fun singlePageDocumentAlwaysReturnsPageOne() {
        val one = paged(page1)
        assertEquals(1, PageMap.pageFor(one, "include an encoder and a decoder"))
    }
}
