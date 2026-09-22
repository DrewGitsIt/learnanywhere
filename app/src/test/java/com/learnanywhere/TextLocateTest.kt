package com.learnanywhere

import com.learnanywhere.core.TextLocate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DESIGN §7.2/§7.3 — "verbatim" quotes from a model are never quite verbatim.
 *
 * Each case below is a real drift seen from Gemini or from PDF extraction:
 * smart quotes, an em dash typed as a hyphen, a line wrap turned into a space,
 * a hyphen the PDF put mid-word. The canonical form has to absorb all of them
 * while still refusing to invent a location that isn't there.
 */
class TextLocateTest {

    private val doc = """
        Attention Is All You Need

        The dominant sequence transduction models are based on complex recurrent or
        convolutional neural networks that include an encoder and a decoder.

        We propose a new simple network architecture, the Transformer, based solely on
        attention mechanisms, dispensing with recurrence and convolutions entirely.
    """.trimIndent()

    @Test
    fun findsAnExactQuote() {
        val quote = "We propose a new simple network architecture"
        assertEquals(doc.indexOf(quote), TextLocate.find(doc, quote))
    }

    @Test
    fun findsDespiteCasePunctuationAndWhitespaceDrift() {
        val drifted = listOf(
            "we propose a new simple network architecture",                 // case
            "We propose a new, simple network architecture!",               // punctuation
            "We  propose\na new simple\tnetwork architecture",              // whitespace
            "“We propose a new simple network architecture”",               // smart quotes
            "We propose a new simple net-work architecture"                 // PDF hyphen
        )
        val expected = doc.indexOf("We propose")
        for (q in drifted) {
            assertEquals(expected, TextLocate.find(doc, q), "failed on: $q")
        }
    }

    /** Drift INSIDE the quote costs the long probe, not the location. */
    @Test
    fun shorterProbesRescueADriftedTail() {
        val q = "The dominant sequence transduction models are utterly unlike anything else"
        assertEquals(doc.indexOf("The dominant"), TextLocate.find(doc, q))
    }

    @Test
    fun missesReturnNull() {
        assertNull(TextLocate.find(doc, "Bayesian last layers and their calibration"))
        assertNull(TextLocate.find("", "anything at all here"))
    }

    /** Too short to be a location rather than a coincidence. */
    @Test
    fun refusesQuotesBelowTheProbeFloor() {
        assertNull(TextLocate.find(doc, "the"))
        assertNull(TextLocate.find(doc, "encoder"))          // 7 canonical chars
        assertNull(TextLocate.find(doc, "!!! ... ???"))      // no canonical chars
        assertNotNull(TextLocate.find(doc, "an encoder and a decoder"))
    }

    /** findRange spans the quote, not just its anchor, so a skip range fits. */
    @Test
    fun findRangeCoversTheWholeQuote() {
        val quote = "sequence transduction models are based on"
        val r = assertNotNull(TextLocate.findRange(doc, quote))
        assertEquals(doc.indexOf(quote), r.first)
        assertEquals(doc.indexOf(quote) + quote.length - 1, r.last)

        // Whitespace in the raw text that the quote lacks still lands inside.
        val wrapped = assertNotNull(
            TextLocate.findRange(doc, "models are based on complex recurrent or convolutional")
        )
        assertTrue(doc.substring(wrapped).contains("\n"), "expected to span the line wrap")
        assertTrue(doc.substring(wrapped).endsWith("convolutional"))
    }

    /** The first occurrence wins — callers slice the haystack to disambiguate. */
    @Test
    fun repeatedQuoteResolvesToTheFirstOccurrence() {
        val hay = "the same sentence twice. the same sentence twice."
        assertEquals(0, TextLocate.find(hay, "the same sentence twice"))
    }
}
