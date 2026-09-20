package com.learnanywhere

import com.learnanywhere.core.GeminiBodyBuilder
import com.learnanywhere.core.UrlText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM-only unit tests for the *pure* parts of LearnAnywhere. These run under
 * `./gradlew :app:testDebugUnitTest` and touch no Android APIs, so they are
 * the part of the app you can verify without a device/emulator.
 *
 *  1. GeminiBodyBuilder — the exact request body the free tier expects.
 *  2. UrlText           — arbitrary-content-from-a-URL pipeline.
 */
class LearnAnywherePureTest {

    /** A well-formed generateContent body must contain the required fields. */
    @Test
    fun geminiBodyHasRequiredFields() {
        val pdf = GeminiBodyBuilder.pdfPart("FAKEBASE64")
        val text = GeminiBodyBuilder.textPart("attention is a machine-learning concept", "Wikipedia: Attention")
        val q = "What is positional encoding?"

        val body = GeminiBodyBuilder.generateContent(
            contents = listOf(
                GeminiBodyBuilder.Message("user", listOf(pdf)),
                GeminiBodyBuilder.Message("user", listOf(text)),
                GeminiBodyBuilder.Message("user", listOf(GeminiBodyBuilder.Part(text = q)))
            ),
            systemInstruction = "You are LearnAnywhere. Cite the attached documents. Stay concise.",
            temperature = 0.4f,
            topP = 0.95f,
            maxOutputTokens = 1024
        )

        println("BODY:\n$body\n")

        assertTrue(body.contains("\"mimeType\":\"application/pdf\""), "missing application/pdf")
        assertTrue(body.contains("\"mimeType\":\"text/plain\""), "missing text/plain")
        assertTrue(body.contains("\"maxOutputTokens\":1024"), "missing maxOutputTokens=1024")
        assertTrue(body.contains("\"system_instruction\""), "missing system_instruction")
        val roles = body.split(Regex("\"role\":\"user\"")).size - 1
        assertTrue(roles >= 3, "expected >=3 user messages, got " + roles)
        val open = body.count { it == '{' }; val close = body.count { it == '}' }
        assertEquals(open, close, "unbalanced braces: open=$open close=$close")
    }

    /** Escape() must emit spec-compliant JSON string literals. */
    @Test
    fun escapeProducesValidJsonStringContent() {
        val in1 = "a\"b\\c"
        val out1 = GeminiBodyBuilder.escape(in1)
        assertEquals("a\\\"b\\\\c", out1)

        val in2 = "line1\nline2\ttab"
        val out2 = GeminiBodyBuilder.escape(in2)
        assertEquals("line1\\nline2\\ttab", out2)
    }

    /** URL→text pipeline must drop scripts/styles/tags and keep content. */
    @Test
    fun urlTextStripsBoilerplateAndKeepsContent() {
        val html = """
            <html><head><title>T</title><style>a{color:red}</style>
            <script>console.log('x')</script></head>
            <body><h1>Heading</h1><p>Para&nbsp;<b>bold</b> text</p>
            <ul><li>one</li><li>two</li></ul></body></html>
        """.trimIndent()

        val out = UrlText.stripHtml(html)
        println("STRIPPED: '$out'")

        assertFalse(out.contains("script"),  "script leaked")
        assertFalse(out.contains("style"),   "style leaked")
        assertFalse(out.contains("console"), "console leaked")
        assertTrue(out.contains("Heading"),  "heading lost")
        assertTrue(out.contains("bold"),     "bold lost")
        assertTrue(out.contains("one") && out.contains("two"), "list items lost")
        assertFalse(out.contains("  "),      "double spaces remain")
    }

    // ----------------------------------------------------------------
    // (a) Figure-candidate scoring heuristic (the "is this page a figure?" test)
    // ----------------------------------------------------------------

    /** A mostly-white "text" page scores low; a mostly-colored "figure" page scores high. */
    @Test
    fun imageRatioScoreDistinguishesTextFromFigurePages() {
        // Synthetic 48x48 bitmaps.
        // 1) A "text page": 95% white, 5% dark ink.
        val textPage: (Int, Int) -> Int
        textPage = { x, y -> if ((x + y) % 20 == 0) 0x101010 else 0xffffff }
        val textScore = com.learnanywhere.core.Figures.imageRatioScore(48, 48, textPage)

        // 2) A "figure page": 70% dark-blue content, 30% white background.
        val figurePage: (Int, Int) -> Int
        figurePage = { x, y -> if ((x + y) % 10 < 7) 0x112233 else 0xffffff }
        val figScore = com.learnanywhere.core.Figures.imageRatioScore(48, 48, figurePage)

        println("textScore=" + textScore + "  figScore=" + figScore)

        assertTrue(textScore < 0.30, "text page should be <30% non-white, was $textScore")
        assertTrue(figScore  >= 0.65, "figure page should be >=65% non-white, was $figScore")
        // The threshold the code uses:
        assertTrue(figScore >= 0.65f, "figure page should be flagged as a candidate")
    }

    /** White-ish vs non-white classification. */
    @Test
    fun whiteThresholdIsSensible() {
        // Pure white → deviation 0
        assertEquals(0,  com.learnanywhere.core.Figures.totalDeviationFromWhite(255, 255, 255))
        // Slightly off-white (245,245,245) → deviation 30, still "close to white" (<30? boundary)
        assertEquals(30, com.learnanywhere.core.Figures.totalDeviationFromWhite(245, 245, 245))
        // Obvious ink (0,0,0) → deviation 765
        assertEquals(765, com.learnanywhere.core.Figures.totalDeviationFromWhite(0, 0, 0))
        // Light blue (16,34,51) → non-white
        assertTrue(com.learnanywhere.core.Figures.totalDeviationFromWhite(16, 34, 51) > 30)
    }

    /** (a) Caption prompt is present, non-trivial, and consistent. */
    @Test
    fun captionPromptNamesTheDocumentAndFigure() {
        val p = com.learnanywhere.core.Figures.captionPrompt("Attention Is All You Need", "Page 4")
        println("CAPTION PROMPT: $p")
        assertTrue(p.contains("Attention Is All You Need"))
        assertTrue(p.contains("Page 4"))
        assertTrue(p.contains("ONE short, self-contained sentence"))
        assertTrue(p.contains("No page numbers"))
    }
}
