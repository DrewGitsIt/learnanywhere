package com.learnanywhere

import com.learnanywhere.core.Sections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DESIGN §7.4 — section offsets become first-class.
 *
 * Two contracts are load-bearing and both are checked here: every section's
 * `start` really is where its text sits in the ORIGINAL string (voice seek
 * jumps by that number), and skipping never changes how the surviving text is
 * sectioned other than by removing the holes.
 */
class SectionsOffsetsTest {

    private val para = "This is a sentence. "

    /** The offset contract: text[start] begins the section, every time. */
    @Test
    fun offsetsPointAtTheSectionInTheOriginalText() {
        val text = "  " + para.repeat(20).trim() + "\n\n\n" + para.repeat(20).trim() +
                "\n\n" + para.repeat(200).trim()
        val secs = Sections.splitWithOffsets(text, target = 500)
        assertTrue(secs.size >= 5, "expected several sections, got ${secs.size}")
        for (s in secs) {
            // A section may be several paragraphs joined by "\n\n"; only its
            // first fragment has to sit at `start`.
            val head = s.text.substringBefore("\n\n")
            assertEquals(head, text.substring(s.start, s.start + head.length),
                "section at ${s.start} is not where it says it is")
        }
        // Offsets are strictly increasing — sections walk the document forward.
        assertEquals(secs.map { it.start }.sorted(), secs.map { it.start })
        assertEquals(secs.map { it.start }.distinct().size, secs.size)
    }

    /**
     * With no skip-map the offset-aware path must reproduce the sectioning
     * UiController and the existing suite already depend on — compared against
     * a verbatim copy of the pre-§7 algorithm, not against `split`, which now
     * delegates and would agree with itself.
     */
    @Test
    fun emptySkipMatchesLegacySplit() {
        val texts = listOf(
            para.repeat(20).trim() + "\n\n" + para.repeat(200).trim(),
            "One short paragraph.",
            "",
            "   \n\n   ",
            "A.\n\nB.\n\nC.",
            "  leading space\n\n\n\nmany blank lines\n\ntrailing  ",
            para.repeat(400).trim()
        )
        for (t in texts) {
            val legacy = legacySplit(t, 500)
            assertEquals(legacy, Sections.split(t, target = 500),
                "split() drifted on \"${t.take(20)}…\"")
            assertEquals(legacy, Sections.splitWithOffsets(t, target = 500).map { it.text },
                "splitWithOffsets drifted on \"${t.take(20)}…\"")
        }
    }

    // ---- the pre-§7 implementation, kept as the reference ------------

    private fun legacySplit(text: String, target: Int): List<String> {
        val paras = text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (p0 in paras) {
            var p = p0
            while (p.length > target * 2) {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                val cut = legacyCut(p, target)
                out.add(p.substring(0, cut).trim())
                p = p.substring(cut).trim()
            }
            when {
                sb.isEmpty() -> sb.append(p)
                sb.length < target -> sb.append("\n\n").append(p)
                else -> { out.add(sb.toString()); sb.setLength(0); sb.append(p) }
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun legacyCut(s: String, target: Int): Int {
        var best = -1
        for (i in 0 until minOf(s.length - 1, target)) {
            val c = s[i]
            if ((c == '.' || c == '!' || c == '?') && s[i + 1].isWhitespace()) best = i + 1
        }
        return if (best > 0) best else minOf(target, s.length)
    }

    /** Skipped text never reaches a section, and offsets stay original-relative. */
    @Test
    fun skipRangesAreExcludedAndOffsetsStayOriginal() {
        val head = "SKIPME copyright notice."
        val body = "The body begins here. It continues for a while."
        val tail = "REFERENCES and so on."
        val text = "$head\n\n$body\n\n$tail"
        val skip = listOf(
            0..(head.length - 1),
            (text.length - tail.length)..(text.length - 1)
        )
        val secs = Sections.splitWithOffsets(text, target = 500, skip = skip)
        assertEquals(1, secs.size)
        assertEquals(body, secs[0].text)
        assertEquals(text.indexOf(body), secs[0].start)
        assertTrue(secs.none { it.text.contains("SKIPME") || it.text.contains("REFERENCES") })
    }

    /**
     * A hole inside a paragraph splits it. The halves may still share a
     * section — that is what any two paragraphs under target do — but they are
     * never glued into one run of prose, which would read as a non-sequitur.
     */
    @Test
    fun holeActsAsAParagraphBoundary() {
        val text = "Alpha beta gamma delta epsilon. SKIP THIS PART ENTIRELY. " +
                "Zeta eta theta iota kappa."
        val hole = text.indexOf("SKIP")..(text.indexOf("Zeta") - 1)
        val secs = Sections.splitWithOffsets(text, target = 500, skip = listOf(hole))
        assertEquals(
            listOf("Alpha beta gamma delta epsilon.\n\nZeta eta theta iota kappa."),
            secs.map { it.text }
        )
        assertEquals(0, secs[0].start)

        // With a target the first half already fills, they land in separate
        // sections — each carrying its own true offset.
        val tight = Sections.splitWithOffsets(text, target = 25, skip = listOf(hole))
        assertEquals(
            listOf("Alpha beta gamma delta epsilon.", "Zeta eta theta iota kappa."),
            tight.map { it.text }
        )
        assertEquals(0, tight[0].start)
        assertEquals(text.indexOf("Zeta"), tight[1].start)
    }

    /** Unsorted, overlapping and out-of-bounds ranges are all tolerated. */
    @Test
    fun messySkipRangesAreNormalized() {
        val text = "Alpha beta gamma. Delta epsilon zeta. Eta theta iota."
        val d = text.indexOf("Delta")
        val messy = listOf(
            (text.length + 50)..(text.length + 99),   // wholly out of bounds
            d..(d + 200),                             // runs past the end
            (d + 3)..(d + 6),                         // inside the previous one
            -20..-1                                   // negative
        )
        val secs = Sections.splitWithOffsets(text, target = 500, skip = messy)
        assertEquals(listOf("Alpha beta gamma."), secs.map { it.text })
        assertEquals(0, secs[0].start)
    }

    /** Skipping everything is legal and yields nothing to read. */
    @Test
    fun skippingTheWholeDocumentYieldsNoSections() {
        val text = "Everything here is boilerplate."
        assertEquals(emptyList(), Sections.splitWithOffsets(text, skip = listOf(0..text.length)))
    }

    /** A sentence-cut giant paragraph still reports true offsets. */
    @Test
    fun sentenceCutSectionsCarryTheirOffsets() {
        val text = para.repeat(200).trim()
        val secs = Sections.splitWithOffsets(text, target = 200)
        assertTrue(secs.size > 5)
        for (s in secs) {
            assertEquals(s.text, text.substring(s.start, s.start + s.text.length))
        }
    }
}
