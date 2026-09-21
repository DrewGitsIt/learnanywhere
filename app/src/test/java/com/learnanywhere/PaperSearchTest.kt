package com.learnanywhere

import com.learnanywhere.core.ArxivAtom
import com.learnanywhere.core.Paper
import com.learnanywhere.core.S2Json
import com.learnanywhere.core.mergePapers
import com.learnanywhere.core.papersToJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM-only tests for the `search_papers` core (`core/PaperSearch.kt`).
 *
 * The arXiv fixture is a real `export.arxiv.org/api/query` response captured
 * for `all:"attention is all you need"`, abridged only in the length of the
 * `<summary>` bodies; the long first title is line-wrapped the way arXiv
 * wraps long titles, so the whitespace-collapsing path is exercised against
 * the shape the wire actually produces.
 *
 * The Semantic Scholar fixture is hand-written to the documented Graph API
 * shape: the anonymous endpoint 429s on essentially every capture attempt
 * (it did while writing this), and retry-spamming a shared free pool to get
 * a test fixture is not acceptable.
 *
 * The network half (`agent/PaperSearchTool`) is not covered here — it is pure
 * OkHttp plumbing over these parsers and would need a live network.
 */
class PaperSearchTest {

    // ------------------------------------------------------------------
    // arXiv Atom
    // ------------------------------------------------------------------

    /** Real feed: title unwrapping, author list, year, PDF link, abs page. */
    @Test
    fun arxivParsesRealFeed() {
        val papers = ArxivAtom.parse(ARXIV_FEED)
        assertEquals(3, papers.size, "expected 3 entries, got ${papers.size}")

        // Wrapped title must come back as a single line with single spaces.
        val first = papers[0]
        assertTrue(first.title.startsWith("Tool Attention Is All You Need: Dynamic Tool Gating"))
        assertFalse(first.title.contains("\n"), "newline leaked into title")
        assertFalse(first.title.contains("  "), "double space left in title")
        assertEquals(2026, first.year)
        assertEquals(listOf("Anuj Sadani", "Deepak Kumar"), first.authors)
        assertEquals("https://arxiv.org/pdf/2604.21816v1", first.pdfUrl)
        assertEquals("http://arxiv.org/abs/2604.21816v1", first.pageUrl)
        assertEquals("arxiv", first.source)
        assertNull(first.venue, "arXiv preprints carry no venue")
        // Real feed carries `-&gt;` inside the summary.
        assertTrue(first.abstract.contains("47.3k -> 2.4k"), "entity not unescaped")

        val transformer = papers[1]
        assertEquals("Attention Is All You Need", transformer.title)
        assertEquals(2017, transformer.year)
        assertEquals(8, transformer.authors.size)
        assertEquals("Ashish Vaswani", transformer.authors.first())
        assertEquals("Illia Polosukhin", transformer.authors.last())
        assertTrue(transformer.abstract.startsWith("The dominant sequence transduction models"))
        assertFalse(transformer.abstract.contains("\n"), "newline leaked into abstract")
    }

    /** `title="pdf"` is the only stable marker; attribute order varies. */
    @Test
    fun arxivFindsPdfLinkRegardlessOfAttributeOrder() {
        val xml = """
            <feed><entry>
              <id>http://arxiv.org/abs/9999.00001v1</id>
              <title>Reordered Attributes</title>
              <link rel="alternate" href="https://arxiv.org/abs/9999.00001v1" type="text/html"/>
              <link title="pdf" type="application/pdf" href="https://arxiv.org/pdf/9999.00001v1" rel="related"/>
              <summary>Short.</summary>
              <published>2099-01-02T00:00:00Z</published>
              <author><name>A Person</name></author>
            </entry></feed>
        """.trimIndent()
        val p = ArxivAtom.parse(xml).single()
        assertEquals("https://arxiv.org/pdf/9999.00001v1", p.pdfUrl)
        assertEquals(2099, p.year)
    }

    /** `&amp;` must unescape last, or `&amp;lt;` would collapse into `<`. */
    @Test
    fun arxivUnescapesEntitiesWithoutDoubleUnescaping() {
        val xml = """
            <feed><entry>
              <id>http://arxiv.org/abs/9999.00002v1</id>
              <title>Tags &amp;lt;eos&amp;gt; &amp; Quotes &quot;q&quot; &apos;a&apos;</title>
              <summary>A &lt;b&gt; tag, an &amp; ampersand, and a literal &amp;amp; entity.</summary>
              <published>2020-07-04T00:00:00Z</published>
              <author><name>E Scape</name></author>
            </entry></feed>
        """.trimIndent()
        val p = ArxivAtom.parse(xml).single()
        assertEquals("Tags &lt;eos&gt; & Quotes \"q\" 'a'", p.title)
        assertEquals("A <b> tag, an & ampersand, and a literal &amp; entity.", p.abstract)
    }

    /** A junk entry is dropped; the good entries on either side survive. */
    @Test
    fun arxivSkipsMalformedEntryWithoutCrashing() {
        val xml = """
            <feed>
            <entry>
              <id>http://arxiv.org/abs/1111.00001v1</id>
              <title>Good One</title>
              <summary>Fine.</summary>
              <published>2011-01-01T00:00:00Z</published>
              <author><name>First Author</name></author>
            </entry>
            <entry>
              <id>http://arxiv.org/abs/2222.00002v1
              <title>Unterminated title
              <author><name>Broken
            </entry>
            <entry>
              <id>http://arxiv.org/abs/3333.00003v1</id>
              <title>Good Two</title>
              <published>2033-03-03T00:00:00Z</published>
            </entry>
            </feed>
        """.trimIndent()
        val papers = ArxivAtom.parse(xml)
        assertEquals(listOf("Good One", "Good Two"), papers.map { it.title })
        // Entry 3 has no authors/summary/pdf at all — still usable, just sparse.
        assertEquals(emptyList(), papers[1].authors)
        assertEquals("", papers[1].abstract)
        assertNull(papers[1].pdfUrl)
        assertEquals(2033, papers[1].year)
    }

    /** Total garbage must yield an empty list, never an exception. */
    @Test
    fun arxivParseSurvivesGarbage() {
        assertEquals(emptyList(), ArxivAtom.parse(""))
        assertEquals(emptyList(), ArxivAtom.parse("not xml at all <entry"))
        assertEquals(emptyList(), ArxivAtom.parse("<feed><entry><title>   </title></entry></feed>"))
    }

    // ------------------------------------------------------------------
    // Semantic Scholar
    // ------------------------------------------------------------------

    /** Missing openAccessPdf falls back to externalIds.ArXiv; nulls tolerated. */
    @Test
    fun s2ParsesNullsAndFallsBackToArxivPdf() {
        val papers = S2Json.parse(S2_RESPONSE)
        assertEquals(2, papers.size, "the untitled record must be dropped")

        val vaswani = papers[0]
        assertEquals("Attention is All you Need", vaswani.title)
        assertEquals(2017, vaswani.year)
        assertEquals("Neural Information Processing Systems", vaswani.venue)
        assertEquals(listOf("Ashish Vaswani", "Noam M. Shazeer", "Niki Parmar"), vaswani.authors)
        // openAccessPdf was null -> synthesized from externalIds.ArXiv.
        assertEquals("https://arxiv.org/pdf/1706.03762", vaswani.pdfUrl)
        assertEquals("s2", vaswani.source)

        val bahdanau = papers[1]
        assertEquals("", bahdanau.abstract, "null abstract must become empty, not crash")
        assertNull(bahdanau.venue, "empty venue must become null")
        assertNull(bahdanau.year, "missing year must become null")
        // openAccessPdf present -> preferred over the arXiv fallback.
        assertEquals("https://arxiv.org/pdf/1409.0473.pdf", bahdanau.pdfUrl)
        assertTrue(bahdanau.pageUrl!!.endsWith("/paper/fa72afa9b2cbc8f0d7b05d52548906610ffbb9c5"))
    }

    /** A 429 body or truncated JSON must parse to nothing, not throw. */
    @Test
    fun s2ParseSurvivesErrorBodies() {
        val rateLimited = """{"message": "Too Many Requests. Please wait and try again", "code": "429"}"""
        assertEquals(emptyList(), S2Json.parse(rateLimited))
        assertEquals(emptyList(), S2Json.parse("""{"total":1,"data":[{"title":"Cut off"""))
        assertEquals(emptyList(), S2Json.parse(""))
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    /** Same paper from both sources: keep the one that has a PDF. */
    @Test
    fun mergeDedupesByNormalizedTitlePreferringPdf() {
        val primary = listOf(
            paper("Attention Is All You Need!", pdf = null, source = "arxiv"),
            paper("A Primary Only Paper", pdf = "https://a/1.pdf", source = "arxiv")
        )
        val secondary = listOf(
            paper("attention is all you need", pdf = "https://s2/att.pdf", source = "s2"),
            paper("A Secondary Only Paper", pdf = null, source = "s2")
        )
        val merged = mergePapers(primary, secondary, max = 10)

        assertEquals(3, merged.size, "punctuation/case-only variants must collapse")
        // Upgraded duplicate keeps position 0 (primary order is preserved).
        assertEquals("https://s2/att.pdf", merged[0].pdfUrl)
        assertEquals("s2", merged[0].source)
        assertEquals("A Primary Only Paper", merged[1].title)
        assertEquals("A Secondary Only Paper", merged[2].title)
    }

    /** Primary wins ties (both have a PDF) and `max` caps the result. */
    @Test
    fun mergePrefersPrimaryOnTiesAndCaps() {
        val primary = listOf(
            paper("Shared Paper", pdf = "https://arxiv/shared.pdf", source = "arxiv"),
            paper("Second", pdf = null, source = "arxiv"),
            paper("Third", pdf = null, source = "arxiv")
        )
        val secondary = listOf(paper("shared paper", pdf = "https://s2/shared.pdf", source = "s2"))

        val all = mergePapers(primary, secondary, max = 10)
        assertEquals(3, all.size)
        assertEquals("arxiv", all[0].source, "primary must win when both have a PDF")

        assertEquals(2, mergePapers(primary, secondary, max = 2).size)
        assertEquals(emptyList(), mergePapers(primary, secondary, max = 0))
    }

    // ------------------------------------------------------------------
    // Model-facing JSON
    // ------------------------------------------------------------------

    /** Abstracts cut on a word boundary; no mid-word truncation. */
    @Test
    fun papersToJsonTruncatesAbstractOnWordBoundary() {
        val abstract = "The dominant sequence transduction models are based on complex " +
                "recurrent or convolutional neural networks in an encoder-decoder configuration."
        val json = papersToJson(listOf(paper("T", abstract = abstract)), maxAbstractChars = 40)

        val cut = Regex("\"abstract\":\"([^\"]*)\"").find(json)!!.groupValues[1]
        assertTrue(cut.endsWith("…"), "missing ellipsis: '$cut'")
        assertTrue(cut.length <= 41, "truncated abstract too long: ${cut.length}")
        assertTrue(abstract.startsWith(cut.dropLast(1)), "truncation must be a clean prefix")
        assertFalse(cut.dropLast(1).endsWith(" "), "trailing space before ellipsis")
        // 40 chars lands inside "models"; the partial word must be dropped.
        assertFalse(cut.contains("mode"), "cut mid-word: '$cut'")

        // Short abstracts are passed through untouched.
        val short = papersToJson(listOf(paper("T", abstract = "Short one.")), maxAbstractChars = 350)
        assertTrue(short.contains("\"abstract\":\"Short one.\""))
    }

    /** Three authors plus "et al."; null/empty fields are omitted entirely. */
    @Test
    fun papersToJsonFormatsAuthorsAndOmitsEmptyFields() {
        val many = Paper(
            title = "Attention Is All You Need",
            authors = listOf("Ashish Vaswani", "Noam Shazeer", "Niki Parmar", "Jakob Uszkoreit"),
            year = 2017,
            abstract = "Transformers.",
            pdfUrl = "https://arxiv.org/pdf/1706.03762v7",
            pageUrl = "http://arxiv.org/abs/1706.03762v7",
            venue = "NeurIPS",
            source = "arxiv"
        )
        val sparse = Paper(
            title = "Bare Record",
            authors = emptyList(),
            year = null,
            abstract = "",
            pdfUrl = null,
            pageUrl = null,
            venue = null,
            source = "s2"
        )
        val json = papersToJson(listOf(many, sparse))
        println("PAPERS JSON: $json")

        assertTrue(json.contains("\"authors\":\"Ashish Vaswani, Noam Shazeer, Niki Parmar et al.\""),
            "et-al formatting wrong: $json")
        assertTrue(json.contains("\"year\":2017"), "year must be a bare number")
        assertTrue(json.contains("\"pdf_url\":\"https://arxiv.org/pdf/1706.03762v7\""))
        assertTrue(json.contains("\"page_url\":\"http://arxiv.org/abs/1706.03762v7\""))

        // The sparse record must be exactly two fields — nothing null emitted.
        val bare = json.substringAfter("{\"title\":\"Bare Record\"").substringBefore("}")
        assertEquals(",\"source\":\"s2\"", bare, "null/empty fields leaked: $bare")
        assertFalse(json.contains("null"), "literal null in model-facing JSON: $json")

        // Exactly three authors => no "et al.".
        val three = papersToJson(listOf(paper("T", authors = listOf("A One", "B Two", "C Three"))))
        assertTrue(three.contains("\"authors\":\"A One, B Two, C Three\""), three)
        assertFalse(three.contains("et al."), three)
    }

    /** Empty result set carries a hint the model can act on. */
    @Test
    fun papersToJsonEmptyCarriesNote() {
        val json = papersToJson(emptyList())
        assertEquals("{\"results\":[],\"note\":\"no papers found — try different terms\"}", json)
    }

    /** Quotes/backslashes in titles must not break the functionResponse JSON. */
    @Test
    fun papersToJsonEscapesTitles() {
        val json = papersToJson(listOf(paper("A \"quoted\" \\ title\nwrapped")))
        assertTrue(json.contains("""\"quoted\""""), json)
        assertTrue(json.contains("""\\"""), json)
        assertFalse(json.contains("\n"), "raw newline in JSON string")
        assertEquals(json.count { it == '{' }, json.count { it == '}' }, "unbalanced braces")
    }

    // ------------------------------------------------------------------

    private fun paper(
        title: String,
        authors: List<String> = listOf("Solo Author"),
        year: Int? = 2020,
        abstract: String = "",
        pdf: String? = null,
        source: String = "arxiv"
    ) = Paper(title, authors, year, abstract, pdf, null, null, source)

    private companion object {

        /**
         * Captured from
         * `export.arxiv.org/api/query?search_query=all:"attention+is+all+you+need"&max_results=3`.
         * Summaries abridged; the first title wrapped as arXiv wraps long ones.
         */
        const val ARXIV_FEED = """<?xml version='1.0' encoding='UTF-8'?>
<feed xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:arxiv="http://arxiv.org/schemas/atom" xmlns="http://www.w3.org/2005/Atom">
  <id>https://arxiv.org/api/O5QMkdJga4mBhrNbadDqJyg4Xzs</id>
  <title>arXiv Query: search_query=all:"attention is all you need"&amp;id_list=&amp;start=0&amp;max_results=3</title>
  <updated>2026-09-21T21:09:11Z</updated>
  <opensearch:totalResults>47</opensearch:totalResults>
  <entry>
    <id>http://arxiv.org/abs/2604.21816v1</id>
    <title>Tool Attention Is All You Need: Dynamic Tool Gating and Lazy Schema
  Loading for Eliminating the MCP/Tools Tax in Scalable Agentic Workflows</title>
    <updated>2026-04-23T16:10:00Z</updated>
    <link href="https://arxiv.org/abs/2604.21816v1" rel="alternate" type="text/html"/>
    <link href="https://arxiv.org/pdf/2604.21816v1" rel="related" type="application/pdf" title="pdf"/>
    <summary>The Model Context Protocol (MCP) has become a common interface for
connecting large language model (LLM) agents to external tools, but its reliance
on stateless, eager schema injection imposes a hidden per-turn overhead. In this
simulation, Tool Attention directly reduces measured per-turn tool tokens by
95.0% (47.3k -&gt; 2.4k) and raises effective context utilization from 24% to
91%.</summary>
    <category term="cs.AI" scheme="http://arxiv.org/schemas/atom"/>
    <published>2026-04-23T16:10:00Z</published>
    <arxiv:comment>21 pages</arxiv:comment>
    <arxiv:primary_category term="cs.AI"/>
    <author>
      <name>Anuj Sadani</name>
    </author>
    <author>
      <name>Deepak Kumar</name>
    </author>
  </entry>
  <entry>
    <id>http://arxiv.org/abs/1706.03762v7</id>
    <title>Attention Is All You Need</title>
    <updated>2023-08-02T00:41:18Z</updated>
    <link href="https://arxiv.org/abs/1706.03762v7" rel="alternate" type="text/html"/>
    <link href="https://arxiv.org/pdf/1706.03762v7" rel="related" type="application/pdf" title="pdf"/>
    <summary>The dominant sequence transduction models are based on complex
recurrent or convolutional neural networks in an encoder-decoder configuration.
We propose a new simple network architecture, the Transformer, based solely on
attention mechanisms, dispensing with recurrence and convolutions
entirely.</summary>
    <category term="cs.CL" scheme="http://arxiv.org/schemas/atom"/>
    <category term="cs.LG" scheme="http://arxiv.org/schemas/atom"/>
    <published>2017-06-12T17:57:34Z</published>
    <arxiv:comment>15 pages, 5 figures</arxiv:comment>
    <arxiv:primary_category term="cs.CL"/>
    <author>
      <name>Ashish Vaswani</name>
    </author>
    <author>
      <name>Noam Shazeer</name>
    </author>
    <author>
      <name>Niki Parmar</name>
    </author>
    <author>
      <name>Jakob Uszkoreit</name>
    </author>
    <author>
      <name>Llion Jones</name>
    </author>
    <author>
      <name>Aidan N. Gomez</name>
    </author>
    <author>
      <name>Lukasz Kaiser</name>
    </author>
    <author>
      <name>Illia Polosukhin</name>
    </author>
  </entry>
  <entry>
    <id>http://arxiv.org/abs/2104.04692v3</id>
    <title>Not All Attention Is All You Need</title>
    <updated>2021-06-01T03:09:39Z</updated>
    <link href="https://arxiv.org/abs/2104.04692v3" rel="alternate" type="text/html"/>
    <link href="https://arxiv.org/pdf/2104.04692v3" rel="related" type="application/pdf" title="pdf"/>
    <summary>Beyond the success story of pre-trained language models (PrLMs) in
recent natural language processing, they are susceptible to over-fitting due to
unusual large model size. In this paper, we propose a novel dropout method named
AttendOut.</summary>
    <category term="cs.CL" scheme="http://arxiv.org/schemas/atom"/>
    <published>2021-04-10T06:24:52Z</published>
    <arxiv:primary_category term="cs.CL"/>
    <author>
      <name>Hongqiu Wu</name>
    </author>
    <author>
      <name>Hai Zhao</name>
    </author>
    <author>
      <name>Min Zhang</name>
    </author>
  </entry>
</feed>
"""

        /**
         * Hand-written to the documented `/graph/v1/paper/search` shape (the
         * live anonymous endpoint 429s). Covers: null `openAccessPdf` with an
         * ArXiv external id, a present `openAccessPdf`, a null `abstract`, an
         * empty `venue`, a missing `year`, and a record with no title.
         */
        const val S2_RESPONSE = """
{
  "total": 3,
  "data": [
    {
      "paperId": "204e3073870fae3d05bcbc2f6a8e263d9b72e776",
      "title": "Attention is All you Need",
      "abstract": "The dominant sequence transduction models are based on complex recurrent or convolutional neural networks.",
      "year": 2017,
      "venue": "Neural Information Processing Systems",
      "authors": [
        {"authorId": "40348417", "name": "Ashish Vaswani"},
        {"authorId": "1846258", "name": "Noam M. Shazeer"},
        {"authorId": "3877127", "name": "Niki Parmar"}
      ],
      "externalIds": {"ArXiv": "1706.03762", "DBLP": "conf/nips/VaswaniSPUJGKP17", "CorpusId": 13756489},
      "openAccessPdf": null
    },
    {
      "paperId": "fa72afa9b2cbc8f0d7b05d52548906610ffbb9c5",
      "title": "Neural Machine Translation by Jointly Learning to Align and Translate",
      "abstract": null,
      "venue": "",
      "authors": [{"authorId": "1929139", "name": "Dzmitry Bahdanau"}],
      "externalIds": {"ArXiv": "1409.0473", "CorpusId": 11212020},
      "openAccessPdf": {"url": "https://arxiv.org/pdf/1409.0473.pdf", "status": "GREEN"}
    },
    {
      "paperId": "0000000000000000000000000000000000000000",
      "title": null,
      "abstract": "A record with no title is not a usable result.",
      "year": 1999,
      "authors": [],
      "externalIds": {},
      "openAccessPdf": null
    }
  ]
}
"""
    }
}
