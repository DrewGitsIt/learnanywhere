package com.learnanywhere

import com.learnanywhere.core.TavilySearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM-only unit tests for [TavilySearch] — the pure request/response shaping
 * for the `search_web` agent tool. No network, no Android.
 *
 * Assertions are string-based (`.contains`, regex pulls), same style as
 * [LearnAnywherePureTest]'s GeminiBodyBuilder tests — org.json is not usable
 * here either (it resolves to the Android mockable stub jar under this
 * project's JVM unit test task and throws "not mocked" on every call).
 */
class WebSearchTest {

    private fun field(json: String, key: String): String? =
        Regex("\"$key\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)

    // ------------------------------------------------------------------
    // buildRequestBody
    // ------------------------------------------------------------------

    @Test
    fun buildRequestBodyEscapesQuotesAndNewlines() {
        val body = TavilySearch.buildRequestBody("say \"hi\"\nnext line", 3)
        println("BODY: $body")

        assertTrue(body.contains("\\\"hi\\\""), "embedded quotes must be escaped")
        assertTrue(body.contains("\\n"), "newline must be escaped")
        assertFalse(body.contains("\"hi\"\n"), "raw unescaped quote/newline must not leak through")
        val open = body.count { it == '{' }; val close = body.count { it == '}' }
        assertEquals(open, close, "unbalanced braces: open=$open close=$close")
    }

    @Test
    fun buildRequestBodyIncludesFixedFields() {
        val body = TavilySearch.buildRequestBody("positional encoding", 5)
        println("BODY: $body")

        assertTrue(body.contains("\"query\":\"positional encoding\""))
        assertTrue(body.contains("\"max_results\":5"))
        assertTrue(body.contains("\"include_answer\":true"))
        assertTrue(body.contains("\"search_depth\":\"basic\""))
    }

    // ------------------------------------------------------------------
    // parseResponse
    // ------------------------------------------------------------------

    @Test
    fun parseResponseCapsAtFiveAndKeepsAnswer() {
        val results = (1..6).joinToString(",") { i ->
            """{"title":"Result $i","url":"https://example.com/$i","content":"Content for result $i.","score":0.9}"""
        }
        val fixture = """
            {"query":"attention is all you need",
             "answer":"It's a 2017 paper introducing the Transformer architecture.",
             "results":[$results],
             "response_time":0.42}
        """.trimIndent()

        val out = TavilySearch.parseResponse(fixture)
        println("OUT: $out")

        assertEquals("It's a 2017 paper introducing the Transformer architecture.", field(out, "answer"))
        val resultBlocks = Regex("\\{\"title\":").findAll(out).count()
        assertEquals(5, resultBlocks, "must cap at 5 results")
        assertTrue(out.contains("\"title\":\"Result 1\""))
        assertTrue(out.contains("\"url\":\"https://example.com/1\""))
        assertTrue(out.contains("\"snippet\":\"Content for result 1.\""))
        assertFalse(out.contains("Result 6"), "6th result must be dropped by the cap")
    }

    @Test
    fun parseResponseTruncatesSnippetOnWordBoundary() {
        val longContent = "word ".repeat(200).trim()  // way over 400 chars, single spaces
        val fixture = """{"results":[{"title":"T","url":"https://example.com","content":"$longContent"}]}"""

        val out = TavilySearch.parseResponse(fixture, maxSnippetChars = 50)
        val snippet = field(out, "snippet")!!
        println("SNIPPET: '$snippet'")

        assertTrue(snippet.endsWith("…"), "truncated snippet must end with an ellipsis")
        assertTrue(snippet.length <= 51, "snippet should be roughly maxSnippetChars, was ${snippet.length}")
        val withoutEllipsis = snippet.removeSuffix("…")
        assertFalse(withoutEllipsis.endsWith(" "), "must not leave a trailing space before the ellipsis")
        assertTrue(longContent.startsWith(withoutEllipsis), "must be an exact prefix of the original content")
    }

    @Test
    fun parseResponseOmitsBlankAnswer() {
        val fixture = """{"answer":"   ","results":[{"title":"T","url":"https://example.com","content":"short content"}]}"""

        val out = TavilySearch.parseResponse(fixture)
        println("OUT: $out")

        assertFalse(out.contains("\"answer\""), "blank answer must be omitted")
        assertTrue(out.contains("\"title\":\"T\""))
    }

    @Test
    fun parseResponseToleratesMissingContentAndUrl() {
        // No "content", no "url" for this result — must not crash.
        val fixture = """{"results":[{"title":"No content or url"}]}"""

        val out = TavilySearch.parseResponse(fixture)
        println("OUT: $out")

        assertTrue(out.contains("\"title\":\"No content or url\""))
        assertEquals("", field(out, "url"))
        assertEquals("", field(out, "snippet"))
    }

    @Test
    fun parseResponseEmptyResultsAndNoAnswerYieldsNoteShape() {
        val fixture = """{"results":[]}"""

        val out = TavilySearch.parseResponse(fixture)
        println("OUT: $out")

        assertEquals("""{"results":[],"note":"no web results"}""", out)
    }

    // ------------------------------------------------------------------
    // summarizeError
    // ------------------------------------------------------------------

    @Test
    fun summarizeErrorMentionsSettingsFor401() {
        val msg = TavilySearch.summarizeError(401, """{"error":"Unauthorized: invalid API key"}""")
        println("401: $msg")
        assertTrue(msg.contains("401"))
        assertTrue(msg.contains("Settings"))
    }

    @Test
    fun summarizeErrorMentionsQuotaFor429() {
        val msg = TavilySearch.summarizeError(429, """{"error":"rate limit exceeded"}""")
        println("429: $msg")
        assertTrue(msg.contains("429"))
        assertTrue(msg.contains("quota", ignoreCase = true))
    }

    @Test
    fun summarizeErrorFallsBackToRawSnippetWhenBodyUnparseable() {
        val msg = TavilySearch.summarizeError(500, "Internal Server Error, upstream gateway timed out")
        println("500: $msg")
        assertTrue(msg.contains("500"))
        assertTrue(msg.contains("Internal Server Error"))
    }
}
