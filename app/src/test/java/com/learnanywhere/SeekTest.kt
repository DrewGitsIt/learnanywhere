package com.learnanywhere

import com.learnanywhere.core.Sections
import com.learnanywhere.core.Seek
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Voice seek's offset→section mapping (DESIGN §7.3).
 *
 * Every case here is one the live path can actually produce: the quote landed
 * in a hole the skip-map left, the quote landed past everything because the
 * sectioning was rebuilt with a fresher skip-map, the document turned out to
 * have no sections at all. All of them must answer with a playable index
 * rather than an exception — a seek that throws costs the user the spoken
 * answer too.
 */
class SeekTest {

    private fun sections(vararg starts: Int): List<Sections.Section> =
        starts.map { Sections.Section("section at $it", it) }

    @Test fun `empty list answers zero`() {
        assertEquals(0, Seek.sectionIndexFor(emptyList(), 0))
        assertEquals(0, Seek.sectionIndexFor(emptyList(), 5_000))
    }

    @Test fun `offset before the first section falls to the beginning`() {
        // Head boilerplate skipped: section 0 starts well into the text.
        val secs = sections(400, 1600, 2800)
        assertEquals(0, Seek.sectionIndexFor(secs, 0))
        assertEquals(0, Seek.sectionIndexFor(secs, 399))
    }

    @Test fun `offset between starts picks the section containing it`() {
        val secs = sections(0, 1200, 2400, 3600)
        assertEquals(0, Seek.sectionIndexFor(secs, 11))
        assertEquals(1, Seek.sectionIndexFor(secs, 1201))
        assertEquals(1, Seek.sectionIndexFor(secs, 2399))
        assertEquals(2, Seek.sectionIndexFor(secs, 3000))
    }

    @Test fun `exact boundary belongs to the section that starts there`() {
        val secs = sections(0, 1200, 2400)
        assertEquals(0, Seek.sectionIndexFor(secs, 0))
        assertEquals(1, Seek.sectionIndexFor(secs, 1200))
        assertEquals(2, Seek.sectionIndexFor(secs, 2400))
    }

    @Test fun `offset past the end lands on the last section`() {
        val secs = sections(0, 1200, 2400)
        assertEquals(2, Seek.sectionIndexFor(secs, 2401))
        assertEquals(2, Seek.sectionIndexFor(secs, Int.MAX_VALUE))
    }

    @Test fun `negative offset is the beginning, not a crash`() {
        assertEquals(0, Seek.sectionIndexFor(sections(0, 900), -1))
        assertEquals(0, Seek.sectionIndexFor(sections(0, 900), Int.MIN_VALUE))
    }

    @Test fun `single section swallows every offset`() {
        val secs = sections(250)
        assertEquals(0, Seek.sectionIndexFor(secs, 0))
        assertEquals(0, Seek.sectionIndexFor(secs, 250))
        assertEquals(0, Seek.sectionIndexFor(secs, 99_999))
    }

    /**
     * The real pipeline end to end: locate a quote in the stored text, then
     * map its offset to a section — over sectioning that skipped a region, so
     * the index and the offsets must come from the SAME geometry (§7.4).
     */
    @Test fun `quote offset maps to the section the quote is in`() {
        val text = buildString {
            append("arXiv:2401.00001v1  [cs.LG]  2 Jan 2024\n\n")
            append("Abstract. We study calibration.\n\n")
            append("A".repeat(1400)).append("\n\n")
            append("Bayesian last layers keep the features frozen.\n\n")
            append("B".repeat(1400)).append("\n\n")
        }
        val skip = listOf(0..39)      // the front-matter banner
        val secs = Sections.splitWithOffsets(text, skip = skip)
        val offset = com.learnanywhere.core.TextLocate.find(
            text, "Bayesian last layers keep the features frozen")!!
        val idx = Seek.sectionIndexFor(secs, offset)
        // Whichever section it is, it must be the one actually holding the line.
        assert(secs[idx].text.contains("Bayesian last layers")) {
            "section $idx does not contain the quote: ${secs[idx].text.take(80)}"
        }
        // And the skipped banner is nowhere in the read text.
        assert(secs.none { it.text.contains("arXiv:2401") }) { "front matter was not skipped" }
    }
}
