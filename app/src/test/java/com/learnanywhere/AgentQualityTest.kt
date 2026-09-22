package com.learnanywhere

import com.learnanywhere.agent.Gemini
import com.learnanywhere.agent.LearnAnywhereAgent
import com.learnanywhere.agent.ReplyJson
import com.learnanywhere.agent.buildSystemPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM-only tests for the agent-layer half of DESIGN §7: the answer-quality
 * system prompt (§7.1) and the voice-seek plumbing (§7.3). Nothing here
 * touches Android APIs or the network — [buildSystemPrompt] is a pure
 * function and [ReplyJson] is a pure parser.
 */
class AgentQualityTest {

    // ------------------------------------------------------------------
    // §7.3 — seek_quote through the reply parser

    /** A navigation reply carries the verbatim quote plus its document. */
    @Test
    fun replyJsonParsesSeekQuote() {
        val quote = "We now review prior work on Bayesian last layers and their calibration."
        val p = ReplyJson.parse(
            """{"answer":"Jumping to the related-work section.",""" +
            """"cited_document":"Attention Is All You Need",""" +
            """"seek_quote":"$quote"}""")
        assertNotNull(p)
        assertEquals("Jumping to the related-work section.", p.answer)
        assertEquals("Attention Is All You Need", p.citedDocument)
        assertEquals(quote, p.seekQuote)
    }

    /** No navigation asked for: the field is absent and parses to null. */
    @Test
    fun replyJsonSeekQuoteAbsentIsNull() {
        val p = ReplyJson.parse("""{"answer":"The encoder has six layers."}""")
        assertNotNull(p)
        assertEquals(null, p.seekQuote, "absent seek_quote must be null, not \"\"")
        // The pre-existing fields keep their behaviour.
        assertEquals(null, p.citedDocument)
        assertEquals(null, p.citedPage)
    }

    /** Blank / whitespace-only quotes are "no jump", not an empty jump target. */
    @Test
    fun replyJsonBlankSeekQuoteIsNull() {
        assertEquals(null, ReplyJson.parse("""{"answer":"Hi.","seek_quote":""}""")?.seekQuote)
        assertEquals(null, ReplyJson.parse("""{"answer":"Hi.","seek_quote":"   "}""")?.seekQuote)
        assertEquals(null, ReplyJson.parse("""{"answer":"Hi.","seek_quote":"\n\t "}""")?.seekQuote)
    }

    /** The schema we send must be valid JSON and must declare seek_quote. */
    @Test
    fun responseSchemaIsValidJsonWithSeekQuote() {
        val o = org.json.JSONObject(LearnAnywhereAgent.RESPONSE_SCHEMA)
        assertEquals("object", o.getString("type"))
        val props = o.getJSONObject("properties")
        listOf("answer", "cited_document", "cited_figure", "cited_page", "seek_quote")
            .forEach { assertTrue(props.has(it), "schema must declare $it") }
        assertEquals("string", props.getJSONObject("seek_quote").getString("type"))
        assertTrue(props.getJSONObject("seek_quote").getString("description").isNotBlank())
        // seek_quote is optional — only `answer` is ever required.
        val required = o.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("answer", required.getString(0))
    }

    // ------------------------------------------------------------------
    // §5 — the prompt is a pure function (prompt-cache alignment)

    /** Same inputs → byte-identical prompt, every time, in every combination. */
    @Test
    fun systemPromptIsDeterministic() {
        for (searchOn in listOf(true, false)) {
            for (toolsOn in listOf(true, false)) {
                val first = buildSystemPrompt(searchOn, toolsOn)
                repeat(3) {
                    assertEquals(first, buildSystemPrompt(searchOn, toolsOn),
                        "prompt must be byte-stable for searchOn=$searchOn toolsOn=$toolsOn")
                }
            }
        }
    }

    /** The four variants really are distinct — the flags still do something. */
    @Test
    fun systemPromptVariantsDiffer() {
        val variants = listOf(true, false).flatMap { s ->
            listOf(true, false).map { t -> buildSystemPrompt(s, t) }
        }
        assertEquals(4, variants.toSet().size, "each searchOn/toolsOn combination is distinct")
        assertTrue(buildSystemPrompt(true, false).contains("Google Search"))
        assertFalse(buildSystemPrompt(false, false).contains("Google Search"))
        assertTrue(buildSystemPrompt(false, true).contains("search_papers"))
        assertFalse(buildSystemPrompt(false, false).contains("search_papers"))
    }

    // ------------------------------------------------------------------
    // §7.1 — answer quality

    /** The hard 2–4 sentence cap is gone, replaced by adaptive length. */
    @Test
    fun systemPromptDropsTheShortAnswerCap() {
        for (searchOn in listOf(true, false)) {
            for (toolsOn in listOf(true, false)) {
                val p = buildSystemPrompt(searchOn, toolsOn)
                assertFalse(p.contains("2–4 sentences"), "en-dash cap must be gone")
                assertFalse(p.contains("2-4 sentences"), "hyphen cap must be gone")
                assertTrue(p.contains("four to eight sentences"),
                    "adaptive default length must be stated")
            }
        }
    }

    /** The teaching persona, doc-grounding and seek guidance are all present. */
    @Test
    fun systemPromptCarriesTeachingAndSeekGuidance() {
        val p = buildSystemPrompt(searchOn = false, toolsOn = false)
        // Teaching persona (§7.1).
        assertTrue(p.contains("excellent teacher"))
        assertTrue(p.contains("intuition"))
        assertTrue(p.contains("takeaway"))
        assertTrue(p.contains("Define every technical"))
        // Grounding directives.
        assertTrue(p.contains("exact numbers"))
        assertTrue(p.contains("reported metrics"))
        // Voice-first rules survive the rewrite.
        assertTrue(p.contains("spoken aloud"))
        assertTrue(p.contains("No markdown"))
        assertTrue(p.contains("charitably"))
        // Voice seek (§7.3).
        assertTrue(p.contains("seek_quote"), "prompt must explain seek_quote")
        assertTrue(p.contains("verbatim sentence"))
        assertTrue(p.contains("ten words"))
        assertTrue(p.contains("Jumping to the"), "spoken-confirmation example")
    }

    // ------------------------------------------------------------------
    // §7.1 — default model bump

    @Test
    fun defaultModelIsThreeEightFlash() {
        assertEquals("gemini-3.8-flash", Gemini.DEFAULT_MODEL)
    }
}
