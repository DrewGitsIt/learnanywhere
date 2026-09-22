package com.learnanywhere

import com.learnanywhere.ui.clampCitedPage
import com.learnanywhere.ui.highlightRange
import com.learnanywhere.ui.resolveCitedDocIndex
import com.learnanywhere.ui.speedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The read-along presentation helpers (DESIGN §6.4/§6.5). These are the parts
 * that index into arrays and into strings, so they are the parts that get to
 * be wrong quietly — hence tests rather than a visual check.
 */
class UiKaraokeTest {

    // ---- highlightRange ----

    @Test fun `highlight spans the spoken sentence`() {
        val text = "First sentence. Second sentence. Third."
        val r = highlightRange(text, "Second sentence.")!!
        assertEquals(16, r.first)
        assertEquals(text.indexOf("Second sentence.") + "Second sentence.".length - 1, r.last)
        assertEquals("Second sentence.", text.substring(r.first, r.last + 1))
    }

    @Test fun `highlight tolerates the whitespace the player trims`() {
        val text = "Alpha beta gamma."
        val r = highlightRange(text, "  beta gamma. \n")!!
        assertEquals("beta gamma.", text.substring(r.first, r.last + 1))
    }

    @Test fun `highlight takes the first occurrence`() {
        val r = highlightRange("go on. go on.", "go on.")!!
        assertEquals(0, r.first)
    }

    @Test fun `highlight is null when absent or empty`() {
        assertNull(highlightRange("some text", "not in there"))
        assertNull(highlightRange("some text", "   "))
        assertNull(highlightRange("some text", ""))
        assertNull(highlightRange("", "anything"))
    }

    // ---- clampCitedPage ----

    @Test fun `cited page is clamped into the document`() {
        assertEquals(1, clampCitedPage(0, 10))
        assertEquals(1, clampCitedPage(-4, 10))
        assertEquals(10, clampCitedPage(99, 10))
        assertEquals(7, clampCitedPage(7, 10))
        assertEquals(1, clampCitedPage(1, 1))
    }

    @Test fun `no page and no pages both yield null`() {
        assertNull(clampCitedPage(null, 10))
        assertNull(clampCitedPage(3, 0))
        assertNull(clampCitedPage(3, -1))
    }

    /** The clamp exists so `figures[page - 1]` is always in range. */
    @Test fun `clamped page always indexes the figure list`() {
        val figures = List(5) { "page ${it + 1}" }
        for (claimed in listOf(-10, 0, 1, 3, 5, 6, 4096)) {
            val p = clampCitedPage(claimed, figures.size)!!
            assertEquals("page $p", figures[p - 1])
        }
    }

    // ---- resolveCitedDocIndex ----

    private val titles = listOf("Attention Is All You Need", "Deep Residual Learning", "My notes")

    @Test fun `exact title match wins`() {
        assertEquals(1, resolveCitedDocIndex(titles, "Deep Residual Learning"))
        assertEquals(1, resolveCitedDocIndex(titles, "deep residual learning"))
        assertEquals(0, resolveCitedDocIndex(titles, "  Attention Is All You Need  "))
    }

    @Test fun `containment resolves paraphrased titles either way`() {
        // Citation is a fragment of the stored title…
        assertEquals(0, resolveCitedDocIndex(titles, "Attention"))
        // …or the stored title is a fragment of the citation.
        assertEquals(2, resolveCitedDocIndex(titles, "My notes (2026-09-22).txt"))
    }

    @Test fun `exact match beats an earlier containment`() {
        val ts = listOf("Notes and appendices", "Notes")
        assertEquals(1, resolveCitedDocIndex(ts, "Notes"))
    }

    @Test fun `unresolvable citations yield null`() {
        assertNull(resolveCitedDocIndex(titles, null))
        assertNull(resolveCitedDocIndex(titles, ""))
        assertNull(resolveCitedDocIndex(titles, "   "))
        assertNull(resolveCitedDocIndex(titles, "A paper we never added"))
        assertNull(resolveCitedDocIndex(emptyList(), "Anything"))
    }

    /** A blank stored title must not swallow every citation via containment. */
    @Test fun `blank library titles never match`() {
        assertNull(resolveCitedDocIndex(listOf("", "  "), "Some Paper"))
    }

    // ---- speedLabel ----

    @Test fun `speed labels read like speeds`() {
        assertEquals("0.75×", speedLabel(0.75f))
        assertEquals("1×", speedLabel(1.0f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("1.5×", speedLabel(1.5f))
        assertEquals("2×", speedLabel(2.0f))
    }
}
