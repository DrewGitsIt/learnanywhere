package com.learnanywhere

import com.learnanywhere.core.Boilerplate
import com.learnanywhere.core.Ranges
import com.learnanywhere.core.Sections
import com.learnanywhere.data.BoilerplateClassifier
import com.learnanywhere.data.SkipStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DESIGN §7.2 — silent boilerplate skipping.
 *
 * The bar these tests hold the heuristics to is asymmetric on purpose: missing
 * a copyright line costs the listener one dull sentence, while skipping a
 * paragraph of the paper costs them the paper. So the arXiv fixture must lose
 * its front matter and its references, the novel fixture must lose nothing at
 * all, and the classifier must survive whatever the model says.
 */
class BoilerplateTest {

    // ---- fixtures ----------------------------------------------------

    private val body = """
        1 Introduction

        Deep networks are routinely deployed without calibrated uncertainty. We ask
        whether a Bayesian treatment of the last layer alone recovers most of the
        benefit at a fraction of the cost.

        2 Method

        We place a Gaussian prior over the final linear layer and integrate it out in
        closed form, leaving the feature extractor deterministic. Training is
        unchanged; inference costs one extra matrix multiply.

        3 Results

        On CIFAR-10 the last-layer posterior reduces expected calibration error from
        7.2% to 1.9% while leaving top-1 accuracy within noise of the deterministic
        baseline.
    """.trimIndent()

    private val arxivDoc = """
        arXiv:2301.04567v2 [cs.LG] 18 Mar 2023

        Preprint. Under review.

        Bayesian Last Layers Are Enough

        Copyright 2023 The Authors. Licensed under CC BY 4.0.

        $body

        Acknowledgments

        We thank the reviewers for their careful reading, and the lab for compute.

        References

        [1] Blundell et al. Weight uncertainty in neural networks. ICML 2015.
        [2] Gal and Ghahramani. Dropout as a Bayesian approximation. ICML 2016.
        [3] Kendall and Gal. What uncertainties do we need? NeurIPS 2017.
    """.trimIndent()

    private val novel = """
        Chapter One

        The rain had been falling since Tuesday, and by Friday the lane outside the
        cottage had given up pretending to be a lane at all.

        Martha put the kettle on because that was what one did, and because the
        alternative was to stand at the window and count the puddles.

        "There are references to this weather in the parish book," she said, to nobody.
        "Nineteen forty-seven. My mother remembered it."
    """.trimIndent()

    // ---- heuristics --------------------------------------------------

    @Test
    fun skipsArxivFrontMatterAndReferencesButNotTheBody() {
        val ranges = Boilerplate.heuristicRanges(arxivDoc)
        assertTrue(ranges.isNotEmpty(), "nothing skipped in an arXiv-shaped document")
        val kept = Boilerplate.keptText(arxivDoc, ranges)

        for (gone in listOf(
            "arXiv:2301.04567v2", "Preprint. Under review.", "Copyright 2023",
            "Licensed under CC BY", "Blundell", "Dropout as a Bayesian approximation",
            "We thank the reviewers"
        )) assertFalse(kept.contains(gone), "should have been skipped: $gone")

        for (stays in listOf(
            "Bayesian Last Layers Are Enough", "1 Introduction", "2 Method", "3 Results",
            "expected calibration error", "one extra matrix multiply", "CIFAR-10"
        )) assertTrue(kept.contains(stays), "should have been kept: $stays")
    }

    @Test
    fun heuristicsAreSortedMergedAndInBounds() {
        val ranges = Boilerplate.heuristicRanges(arxivDoc)
        assertEquals(ranges, ranges.sortedBy { it.first })
        for (i in 1 until ranges.size) {
            assertTrue(ranges[i].first > ranges[i - 1].last + 1, "ranges $i and ${i - 1} touch")
        }
        assertTrue(ranges.all { it.first >= 0 && it.last < arxivDoc.length && it.first <= it.last })
    }

    /** Prose that merely says "references" must survive untouched. */
    @Test
    fun conservativeOnPlainProse() {
        assertEquals(emptyList(), Boilerplate.heuristicRanges(novel))
        assertEquals(novel, Boilerplate.keptText(novel, Boilerplate.heuristicRanges(novel)))
        assertEquals(emptyList(), Boilerplate.heuristicRanges(""))
        assertEquals(emptyList(), Boilerplate.heuristicRanges("   \n\n  "))
    }

    /** A references heading early in the document is a body mention, not a tail. */
    @Test
    fun referencesHeadingNearTheTopIsIgnored() {
        val text = "References\n\n" + body + "\n\n" + body
        val kept = Boilerplate.keptText(text, Boilerplate.heuristicRanges(text))
        assertTrue(kept.contains("1 Introduction"))
        assertTrue(kept.contains("3 Results"))
    }

    /** An acknowledgments block long enough to be a mis-detection is left alone. */
    @Test
    fun acknowledgmentsGuardRefusesAHugeBlock() {
        val huge = "Acknowledgments\n\n" + "We thank everyone at length. ".repeat(200)
        val text = body + "\n\n" + huge
        val kept = Boilerplate.keptText(text, Boilerplate.heuristicRanges(text))
        assertTrue(kept.contains("We thank everyone at length."),
            "a 5600-char 'acknowledgments' is a mis-detection; it must not be deleted")
    }

    @Test
    fun keptTextIsUnchangedWhenNothingIsSkipped() {
        assertEquals(arxivDoc, Boilerplate.keptText(arxivDoc, emptyList()))
        assertEquals(arxivDoc, Boilerplate.keptText(arxivDoc, listOf(5..1)))   // empty range
    }

    /** The skip-map drives sectioning too, not just plain playback. */
    @Test
    fun skipMapFeedsReadWithMeSections() {
        val ranges = Boilerplate.heuristicRanges(arxivDoc)
        val secs = Sections.splitWithOffsets(arxivDoc, target = 400, skip = ranges)
        assertTrue(secs.isNotEmpty())
        assertTrue(secs.none { it.text.contains("Blundell") || it.text.contains("arXiv:") })
        assertTrue(secs.any { it.text.contains("expected calibration error") })
        for (s in secs) {
            val head = s.text.substringBefore("\n\n")
            assertEquals(head, arxivDoc.substring(s.start, s.start + head.length))
        }
    }

    // ---- Ranges ------------------------------------------------------

    @Test
    fun rangesNormalizeMergesOverlapsAndClamps() {
        assertEquals(listOf(0..9), Ranges.normalize(listOf(0..4, 5..9), 100))   // adjacent
        assertEquals(listOf(0..9), Ranges.normalize(listOf(3..9, 0..4), 100))   // unsorted
        assertEquals(listOf(0..99), Ranges.normalize(listOf(-5..500), 100))     // clamped
        assertEquals(emptyList(), Ranges.normalize(listOf(0..9), 0))
        assertEquals(listOf(0..4, 10..14), Ranges.normalize(listOf(10..14, 0..4), 100))
        assertEquals(10, Ranges.span(listOf(0..4, 10..14)))
    }

    // ---- classifier --------------------------------------------------

    @Test
    fun classifierWithoutLlmIsExactlyTheHeuristics() = runBlocking {
        assertEquals(
            Boilerplate.heuristicRanges(arxivDoc),
            BoilerplateClassifier(null).classify(arxivDoc)
        )
    }

    /** LLM-located regions are added to the heuristics, never substituted. */
    @Test
    fun classifierUnionsLlmQuotesWithHeuristics() = runBlocking {
        val footnote = "Footnote 4: see the extended citation list in the appendix of the " +
                "companion technical report, which we do not reproduce here."
        val text = body + "\n\n" + footnote + "\n\nReferences\n\n[1] Blundell et al. ICML 2015."
        val llm: suspend (String) -> String? = {
            """[{"start_quote": "Footnote 4: see the extended citation list",
                 "end_quote": "which we do not reproduce here."}]"""
        }
        val heuristics = Boilerplate.heuristicRanges(text)
        val merged = BoilerplateClassifier(llm).classify(text)

        assertTrue(Ranges.span(merged) > Ranges.span(heuristics), "LLM region was dropped")
        val kept = Boilerplate.keptText(text, merged)
        assertFalse(kept.contains("Footnote 4"))
        assertFalse(kept.contains("Blundell"), "heuristics must still apply")
        assertTrue(kept.contains("expected calibration error"))
    }

    /** Whatever the model says, the floor holds and nothing throws. */
    @Test
    fun classifierSurvivesGarbage() = runBlocking {
        val floor = Boilerplate.heuristicRanges(arxivDoc)
        val garbage = listOf<suspend (String) -> String?>(
            { "I'm sorry, I can't help with that." },          // prose, no JSON
            { "[{\"start_quote\": \"unclosed" },                // broken JSON
            { "[]" },                                           // nothing to skip
            { "[{\"start_quote\": 7, \"end_quote\": null}]" },   // wrong types
            { "[{\"start_quote\": \"text that appears nowhere in this document at all\"," +
                    "\"end_quote\": \"nor does this one appear anywhere\"}]" },
            { "[{\"start_quote\": \"References\\n\\n[1] Blundell et al.\"," +
                    "\"end_quote\": \"Deep networks are routinely deployed\"}]" },  // inverted
            { null },                                           // no reply at all
            { throw RuntimeException("quota exhausted") }       // transport blew up
        )
        for ((i, llm) in garbage.withIndex()) {
            assertEquals(floor, BoilerplateClassifier(llm).classify(arxivDoc), "garbage #$i")
        }
    }

    /** A model that calls the entire paper boilerplate is overruled. */
    @Test
    fun classifierRefusesToMuteTheDocument() = runBlocking {
        val llm: suspend (String) -> String? = {
            """[{"start_quote": "Deep networks are routinely deployed without calibrated",
                 "end_quote": "within noise of the deterministic baseline."}]"""
        }
        val kept = Boilerplate.keptText(arxivDoc, BoilerplateClassifier(llm).classify(arxivDoc))
        assertTrue(kept.contains("expected calibration error"),
            "a region covering the body must be refused")
    }

    /** The prompt is capped: a huge document goes in as head + tail. */
    @Test
    fun classifierCapsThePromptSize() = runBlocking {
        val huge = body + "\n\n" + "Filler sentence for bulk. ".repeat(4000) + "\n\nReferences\n\n[1] X."
        var seen = ""
        BoilerplateClassifier({ p -> seen = p; "[]" }).classify(huge)
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.length < huge.length / 2, "prompt was not capped: ${seen.length}")
        assertTrue(seen.contains("Deep networks are routinely deployed"), "head excerpt missing")
        assertTrue(seen.contains("[1] X."), "tail excerpt missing")
    }

    // ---- SkipStore ---------------------------------------------------

    @Test
    fun skipStoreRoundTripsAndDistinguishesEmptyFromAbsent() {
        val dir = tempDir()
        val store = SkipStore(dir)
        assertNull(store.load("doc-1"), "nothing saved yet must read as 'never computed'")

        store.save("doc-1", listOf(0..9, 50..99))
        assertEquals(listOf(0..9, 50..99), store.load("doc-1"))

        store.save("doc-2", emptyList())
        assertEquals(emptyList(), store.load("doc-2"), "'clean document' is not 'unknown'")

        store.remove("doc-1")
        assertNull(store.load("doc-1"))
        assertEquals(emptyList(), store.load("doc-2"), "remove hit the wrong file")
        store.remove("doc-never-existed")     // must not throw
    }

    @Test
    fun skipStoreTreatsACorruptFileAsNeverComputed() {
        val dir = tempDir()
        val store = SkipStore(dir)
        store.save("doc-1", listOf(0..9))
        val f = assertNotNull(dir.listFiles()?.firstOrNull())
        f.writeText("{\"v\":1,\"ranges\":[[0,")          // truncated write
        assertNull(store.load("doc-1"))
        f.writeText("not json at all")
        assertNull(store.load("doc-1"))
        // …and recomputing over the top repairs it.
        store.save("doc-1", listOf(3..4))
        assertEquals(listOf(3..4), store.load("doc-1"))
    }

    /** Ids that sanitize to the same filename must not share a sidecar. */
    @Test
    fun skipStoreSeparatesAwkwardIds() {
        val store = SkipStore(tempDir())
        store.save("https://example.com/a.pdf", listOf(0..1))
        store.save("https://example.com/b.pdf", listOf(2..3))
        store.save("../../etc/passwd", listOf(4..5))
        assertEquals(listOf(0..1), store.load("https://example.com/a.pdf"))
        assertEquals(listOf(2..3), store.load("https://example.com/b.pdf"))
        assertEquals(listOf(4..5), store.load("../../etc/passwd"))
    }

    private fun tempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("skipstore").toFile().apply { deleteOnExit() }
}
