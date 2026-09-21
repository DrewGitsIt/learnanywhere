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

    /** Search grounding: the google_search tool must appear only when enabled. */
    @Test
    fun geminiBodyIncludesSearchToolOnlyWhenEnabled() {
        val msg = listOf(GeminiBodyBuilder.Message("user", listOf(GeminiBodyBuilder.Part(text = "q"))))
        val with = GeminiBodyBuilder.generateContent(msg, null, 0.4f, 0.95f, 64, enableGoogleSearch = true)
        val without = GeminiBodyBuilder.generateContent(msg, null, 0.4f, 0.95f, 64)
        assertTrue(with.contains("\"tools\":[{\"google_search\":{}}]"), "search tool missing")
        assertFalse(without.contains("tools"), "search tool should be absent by default")
        assertEquals(with.count { it == '{' }, with.count { it == '}' }, "unbalanced braces")
    }

    /** Hardening pass (DESIGN §3.7): thinkingConfig, responseSchema, fileData parts. */
    @Test
    fun geminiBodyEmitsThinkingSchemaAndFileData() {
        val body = GeminiBodyBuilder.generateContent(
            contents = listOf(GeminiBodyBuilder.Message("user", listOf(
                GeminiBodyBuilder.Part(text = "grounding"),
                GeminiBodyBuilder.Part(mime = "application/pdf",
                    fileUri = "https://generativelanguage.googleapis.com/v1beta/files/abc")
            ))),
            systemInstruction = null,
            temperature = 1.0f, topP = 0.95f, maxOutputTokens = 4096,
            thinkingLevel = "low",
            responseSchemaJson = """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}"""
        )
        assertTrue(body.contains("\"thinkingConfig\":{\"thinkingLevel\":\"low\"}"), "thinkingConfig missing")
        assertTrue(body.contains("\"responseMimeType\":\"application/json\""), "responseMimeType missing")
        assertTrue(body.contains("\"responseSchema\":{\"type\":\"object\""), "responseSchema missing")
        assertTrue(body.contains("\"fileData\":{\"mimeType\":\"application/pdf\",\"fileUri\":"), "fileData missing")
        assertFalse(body.contains("inlineData"), "fileUri part must not emit inlineData")
        assertEquals(body.count { it == '{' }, body.count { it == '}' }, "unbalanced braces")
    }

    /** Tool loop plumbing: combo tools emission and raw-part echo. */
    @Test
    fun geminiBodyEmitsFunctionDeclarationsComboAndRawParts() {
        val decls = """[{"name":"download_document","description":"d","parameters":{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}}]"""
        val body = GeminiBodyBuilder.generateContent(
            contents = listOf(GeminiBodyBuilder.Message("user", listOf(
                GeminiBodyBuilder.Part(rawJson = """{"functionResponse":{"id":"c1","name":"download_document","response":{"status":"added"}}}""")
            ))),
            systemInstruction = null,
            temperature = 1.0f, topP = 0.95f, maxOutputTokens = 1024,
            enableGoogleSearch = true,
            functionDeclarationsJson = decls
        )
        assertTrue(body.contains("{\"google_search\":{}}"), "google_search missing from combo")
        assertTrue(body.contains("\"functionDeclarations\":[{\"name\":\"download_document\""), "declarations missing")
        assertTrue(body.contains("\"toolConfig\":{\"includeServerSideToolInvocations\":true}"), "toolConfig missing")
        assertTrue(body.contains("\"functionResponse\":{\"id\":\"c1\""), "raw part not passed through verbatim")
        assertEquals(body.count { it == '{' }, body.count { it == '}' }, "unbalanced braces")

        // Search-only requests must NOT carry the combo toolConfig.
        val searchOnly = GeminiBodyBuilder.generateContent(
            listOf(GeminiBodyBuilder.Message("user", listOf(GeminiBodyBuilder.Part(text = "q")))),
            null, 1.0f, 0.95f, 64, enableGoogleSearch = true)
        assertFalse(searchOnly.contains("includeServerSideToolInvocations"))
    }

    /** functionCall responses parse into calls; rawParts keep thoughtSignature verbatim. */
    @Test
    fun geminiParseExtractsFunctionCalls() {
        val g = com.learnanywhere.agent.Gemini({ "key" }, { "model" })
        val body = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "functionCall": {
                          "id": "call_1",
                          "name": "download_document",
                          "args": { "url": "https://arxiv.org/pdf/1706.03762", "title": "Attention Is All You Need" }
                        },
                        "thoughtSignature": "SIG_ABC"
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP",
                  "index": 0
                }
              ]
            }
        """.trimIndent()
        val r = g.parse(body)   // must NOT throw despite empty text
        assertEquals(1, r.functionCalls.size)
        assertEquals("download_document", r.functionCalls[0].name)
        assertEquals("call_1", r.functionCalls[0].id)
        assertTrue(r.functionCalls[0].argsJson.contains("arxiv.org"))
        assertEquals(1, r.rawParts.size)
        assertTrue(r.rawParts[0].contains("SIG_ABC"), "thoughtSignature must survive verbatim")
    }

    /** Structured replies parse into fields; non-JSON falls back to null. */
    @Test
    fun replyJsonParsesStructuredAnswer() {
        val p = com.learnanywhere.agent.ReplyJson.parse(
            """{"answer": "The encoder has six layers.", "cited_document": "Attention Is All You Need", "cited_figure": "Figure 1"}""")
        assertEquals("The encoder has six layers.", p?.answer)
        assertEquals("Attention Is All You Need", p?.citedDocument)
        assertEquals("Figure 1", p?.citedFigure)

        val minimal = com.learnanywhere.agent.ReplyJson.parse("""{"answer":"Hi."}""")
        assertEquals("Hi.", minimal?.answer)
        assertEquals(null, minimal?.citedDocument)

        assertEquals(null, com.learnanywhere.agent.ReplyJson.parse("plain prose, not JSON"))
    }

    /** 429 handling: RetryInfo delay parsing and daily-quota detection. */
    @Test
    fun retryInfoParsing() {
        val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"22s"}]}}"""
        assertEquals(22000L, com.learnanywhere.agent.retryDelayMs(body))
        assertEquals(null, com.learnanywhere.agent.retryDelayMs("{}"))
        assertTrue(com.learnanywhere.agent.isDailyQuota(
            """{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}"""))
        assertFalse(com.learnanywhere.agent.isDailyQuota(
            """{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}"""))
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

    /**
     * The REST API returns pretty-printed JSON (spaces after colons) — the
     * old substring scanner never matched it and every reply parsed as "".
     * This body shape is captured from a real gemini-3.6-flash response.
     */
    @Test
    fun geminiParseReadsPrettyPrintedResponse() {
        val g = com.learnanywhere.agent.Gemini({ "key" }, { "model" })
        val body = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "OK"
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP",
                  "index": 0
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 8,
                "candidatesTokenCount": 2,
                "totalTokenCount": 15,
                "thoughtsTokenCount": 5
              },
              "modelVersion": "gemini-3.6-flash"
            }
        """.trimIndent()
        val r = g.parse(body)
        assertEquals("OK", r.text)
        assertEquals(8, r.promptTokens)
        assertEquals(2, r.completionTokens)
    }

    /** Empty text with a finishReason (e.g. thinking ate the budget) must throw, not return "". */
    @Test
    fun geminiParseThrowsOnEmptyReply() {
        val g = com.learnanywhere.agent.Gemini({ "key" }, { "model" })
        val body = """{ "candidates": [ { "content": {}, "finishReason": "MAX_TOKENS", "index": 0 } ] }"""
        try {
            g.parse(body)
            throw AssertionError("expected GeminiError")
        } catch (e: com.learnanywhere.agent.GeminiError) {
            assertTrue(e.message.contains("MAX_TOKENS"))
        }
    }

    /** Voice loop v2: streamed JSON answers are extracted incrementally. */
    @Test
    fun streamingAnswerExtractorHandlesJsonAndPlain() {
        // JSON reply split across awkward delta boundaries.
        val e = com.learnanywhere.core.StreamingAnswerExtractor()
        val out = StringBuilder()
        listOf("{\"ans", "wer\": \"Hel", "lo \\\"world\\\".", " Bye.\", \"cited_docum",
               "ent\": \"X\"}").forEach { out.append(e.feed(it)) }
        assertEquals("Hello \"world\". Bye.", out.toString())
        assertTrue(e.answerComplete)

        // Plain prose (schema fallback) passes straight through.
        val p = com.learnanywhere.core.StreamingAnswerExtractor()
        assertEquals("Plain ", p.feed("Plain "))
        assertEquals("prose.", p.feed("prose."))
    }

    /** Voice loop v2: sentence chunking for progressive TTS. */
    @Test
    fun sentenceChunkerEmitsCompleteSentences() {
        val c = com.learnanywhere.core.SentenceChunker()
        assertEquals(emptyList<String>(), c.feed("The encoder has"))
        assertEquals(listOf("The encoder has six layers."), c.feed(" six layers. The de"))
        assertEquals(listOf("The decoder mirrors it."), c.feed("coder mirrors it. And"))
        assertEquals("And", c.flush())
        // Decimals don't split sentences.
        val d = com.learnanywhere.core.SentenceChunker()
        assertEquals(emptyList<String>(), d.feed("It weighs 3.5 kg"))
        assertEquals(listOf("It weighs 3.5 kg total."), d.feed(" total. Next"))
    }

    /** Read-with-me: sectionizer respects paragraphs and bounds section size. */
    @Test
    fun sectionsSplitRespectsParagraphsAndSize() {
        val para = "This is a sentence. " // 20 chars
        val text = para.repeat(20).trim() + "\n\n" + para.repeat(20).trim() +
                "\n\n" + para.repeat(200).trim()   // last para is huge (~4000)
        val secs = com.learnanywhere.core.Sections.split(text, target = 500)
        assertTrue(secs.size >= 5, "expected several sections, got ${secs.size}")
        assertTrue(secs.all { it.length <= 1100 }, "a section exceeds 2x target: " +
                secs.maxOf { it.length })
        // Nothing lost (modulo the paragraph separators we re-add).
        val joined = secs.joinToString(" ").replace(Regex("\\s+"), " ")
        assertEquals(text.replace(Regex("\\s+"), " ").length, joined.length)
        // Sections end at sentence boundaries.
        assertTrue(secs.all { it.endsWith(".") }, "section not sentence-aligned")
    }

    /** PDF text re-flow: de-hyphenate wraps, join lines, keep paragraphs. */
    @Test
    fun pdfTextNormalizeReflowsForTts() {
        val raw = "Neural net-\nworks are great.\nThey learn features.\n\nNext paragraph\nhere."
        val out = com.learnanywhere.data.PdfText.normalize(raw)
        assertEquals("Neural networks are great. They learn features.\n\nNext paragraph here.", out)
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
