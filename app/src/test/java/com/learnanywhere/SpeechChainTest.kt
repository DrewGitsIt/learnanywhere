package com.learnanywhere

import com.learnanywhere.audio.SpeechBookkeeper
import com.learnanywhere.core.Sentences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM tests for the pure half of the read-along speech layer (DESIGN §6.4):
 * the rules that decide when a chain of utterances may report completion, and
 * the sentence split that decides what one utterance is. AudiobookPlayer
 * itself is Android-coupled and is not exercised here.
 */
class SpeechChainTest {

    // ----------------------------------------------------------------
    // SpeechBookkeeper — activeText lookup
    // ----------------------------------------------------------------

    /** Every spoken id must resolve to its FULL text, so onStart can publish activeText. */
    @Test
    fun registeredUtterancesResolveToTheirFullText() {
        val b = SpeechBookkeeper()
        val long = "Positional encodings inject order information into the model. ".repeat(10)
        b.register("a", long)
        assertEquals(long, b.textOf("a"))
        assertNull(b.textOf("never-spoken"), "an unknown id must not publish text")
        assertNull(b.textOf(null))
    }

    // ----------------------------------------------------------------
    // SpeechBookkeeper — chain completion
    // ----------------------------------------------------------------

    /** onDone fires once, for the last sentence, and never for a middle one. */
    @Test
    fun chainCompletesOnTheLastSentenceOnlyAndOnlyOnce() {
        val b = SpeechBookkeeper()
        var fired = 0
        val ids = listOf("s1", "s2", "s3")
        ids.forEach { b.register(it, "text of $it") }
        b.armChain(ids) { fired++ }

        assertTrue(b.retire("s1"))
        assertNull(b.claimChainEnd("s1"), "a middle sentence must not end the chain")
        assertTrue(b.isChainMember("s1"))
        assertTrue(b.retire("s2"))
        assertNull(b.claimChainEnd("s2"))

        assertTrue(b.retire("s3"))
        assertNotNull(b.claimChainEnd("s3")).onDone?.invoke()
        assertEquals(1, fired)

        // A duplicate callback from the engine must not fire it again.
        assertNull(b.claimChainEnd("s3"))
        assertEquals(1, fired)
        assertFalse(b.chainArmed)
    }

    /** A chain with no callback still reports its end, so the player can publish idle. */
    @Test
    fun chainWithoutCallbackStillReportsItsEnd() {
        val b = SpeechBookkeeper()
        b.armChain(listOf("only")) { }
        b.armChain(listOf("only"), null)
        val end = assertNotNull(b.claimChainEnd("only"))
        assertNull(end.onDone)
    }

    /** sayOnce / a flushing enqueueSay / play / pause / stop all cancel a pending chain. */
    @Test
    fun interruptCancelsAPendingChain() {
        val b = SpeechBookkeeper()
        var fired = 0
        b.register("s1", "one")
        b.register("s2", "two")
        b.armChain(listOf("s1", "s2")) { fired++ }

        b.interrupt()                        // e.g. pause() or sayOnce() landed
        b.register("x", "interrupting text")

        // The engine still drains its old queue; those callbacks must be inert.
        assertNull(b.textOf("s1"), "a stale start would blank the live highlight")
        assertFalse(b.retire("s2"), "a stale done must not advance anything")
        assertNull(b.claimChainEnd("s2"))
        assertFalse(b.isChainMember("s2"))
        assertEquals(0, fired, "an interrupted chain must never report completion")

        assertEquals("interrupting text", b.textOf("x"))
    }

    /** A second chain replaces the first; only the survivor may complete. */
    @Test
    fun aNewChainSupersedesThePreviousOne() {
        val b = SpeechBookkeeper()
        var first = 0
        var second = 0
        b.register("a1", "one")
        b.armChain(listOf("a1")) { first++ }

        b.interrupt()                        // sayChain(flush = true)
        b.register("b1", "two")
        b.armChain(listOf("b1")) { second++ }

        assertNull(b.claimChainEnd("a1"))
        assertTrue(b.retire("b1"))
        assertNotNull(b.claimChainEnd("b1")).onDone?.invoke()
        assertEquals(0, first)
        assertEquals(1, second)
    }

    /** An engine error is not natural completion — the chain must be dropped. */
    @Test
    fun clearChainSuppressesCompletionAfterAnError() {
        val b = SpeechBookkeeper()
        var fired = 0
        b.register("s1", "one")
        b.armChain(listOf("s1")) { fired++ }

        b.clearChain()                       // handleUttError()
        assertTrue(b.retire("s1"))
        assertNull(b.claimChainEnd("s1"))
        assertEquals(0, fired)
    }

    /** Appending without a flush extends the current request, so the chain survives. */
    @Test
    fun nonFlushingAppendDoesNotCancelTheChain() {
        val b = SpeechBookkeeper()
        var fired = 0
        b.register("s1", "one")
        b.armChain(listOf("s1")) { fired++ }

        b.register("tail", "appended")       // enqueueSay(flush = false)

        assertTrue(b.retire("s1"))
        assertNotNull(b.claimChainEnd("s1")).onDone?.invoke()
        assertEquals(1, fired)
        assertEquals("appended", b.textOf("tail"), "the appended utterance is still live")
    }

    /** The id→text registry must not grow across a long session. */
    @Test
    fun registryIsBoundedByCompletionAndByInterrupt() {
        val b = SpeechBookkeeper()
        repeat(500) { i ->
            b.register("u$i", "chunk $i")
            assertTrue(b.retire("u$i"))
        }
        assertEquals(0, b.liveCount, "completed utterances must be retired")

        repeat(500) { i -> b.register("p$i", "pending $i") }
        assertEquals(500, b.liveCount)
        b.interrupt()
        assertEquals(0, b.liveCount, "a flush must drop every abandoned entry")

        // A stale done for an entry registered before the interrupt is a no-op.
        b.register("z", "z")
        b.interrupt()
        assertFalse(b.retire("z"))
        assertEquals(0, b.liveCount)
    }

    /** forget() drops an utterance the engine refused, without disarming the chain. */
    @Test
    fun forgetDropsARefusedUtterance() {
        val b = SpeechBookkeeper()
        b.register("s1", "one")
        b.armChain(listOf("s1", "s2"), null)
        b.forget("s1")
        assertNull(b.textOf("s1"))
        assertEquals(0, b.liveCount)
        assertTrue(b.chainArmed)
    }

    // ----------------------------------------------------------------
    // Sentences — what one chained utterance is
    // ----------------------------------------------------------------

    /** sayChain callers split complete text with the same rules the stream uses. */
    @Test
    fun sentencesSplitOnRealBoundariesOnly() {
        val out = Sentences.split(
            "Attention is all you need. Is it really? Yes! The value is 3.5 kg here."
        )
        println("SENTENCES: $out")
        assertEquals(4, out.size, "expected 4 sentences, got $out")
        assertEquals("Attention is all you need.", out[0])
        assertEquals("Is it really?", out[1])
        assertEquals("Yes!", out[2])
        assertTrue(out[3].contains("3.5 kg"), "a decimal must not split a sentence")
    }

    /** Nothing to say must yield an empty list — sayChain returns empty and skips onDone. */
    @Test
    fun sentencesSplitOfBlankTextIsEmpty() {
        assertTrue(Sentences.split("").isEmpty())
        assertTrue(Sentences.split("   \n  ").isEmpty())
    }

    /** Splitting must preserve every word, in order — nothing silently unspoken. */
    @Test
    fun sentencesSplitLosesNothing() {
        val text = "First sentence here. Second one follows! Third and last? Trailing tail"
        val words = text.split(Regex("\\s+"))
        assertEquals(words, Sentences.split(text).joinToString(" ").split(Regex("\\s+")))
    }

    /** A run-on with no punctuation still breaks up, so speech starts and rate changes land. */
    @Test
    fun sentencesSplitBreaksUpAPunctuationlessRunOn() {
        val runOn = List(300) { "word$it" }.joinToString(" ")
        val out = Sentences.split(runOn, maxLen = 100)
        assertTrue(out.size > 1, "a 300-word run-on must not stay one utterance")
        assertEquals(300, out.joinToString(" ").split(" ").size, "a word was lost or cut")
    }
}
