package com.learnanywhere.core

/**
 * Pure (Android-free) core for the `search_papers` agent tool: response
 * parsing, merging and the compact JSON we hand back to the model.
 *
 * Everything here is deliberately dependency-free so a plain JVM
 * `kotlin.test` can drive it (same rule the rest of `core/` follows — see
 * [GeminiBodyBuilder]). The network half lives in `agent/PaperSearchTool`.
 *
 * Two parsers, both hand-rolled, for the same reason:
 *  - Atom/XML: `XmlPullParser` is an Android bootclasspath stub, so it throws
 *    "Stub!" under `:app:testDebugUnitTest`. A JVM XML library would be a new
 *    Gradle dependency. The arXiv feed is machine-generated and extremely
 *    regular, so careful string scanning between `<entry>` markers is both
 *    sufficient and testable.
 *  - JSON: `org.json` is likewise an Android stub in unit tests (`Gemini.kt`
 *    avoids it for exactly this reason). [MiniJson] below is a complete,
 *    ~80-line recursive-descent reader — small enough to own, correct enough
 *    to trust with a third-party payload.
 *
 * Nothing in this file throws for bad input: a malformed entry is skipped and
 * whatever parsed is returned. A voice-driven agent would rather read out two
 * papers than an exception.
 */

/** One search hit. [source] is "arxiv" or "s2" (Semantic Scholar). */
data class Paper(
    val title: String,
    val authors: List<String>,
    val year: Int?,
    val abstract: String,
    val pdfUrl: String?,
    val pageUrl: String?,
    val venue: String?,
    val source: String
)

// ----------------------------------------------------------------------
// arXiv Atom feed
// ----------------------------------------------------------------------

object ArxivAtom {

    /**
     * Parse an `export.arxiv.org/api/query` Atom feed into [Paper]s.
     *
     * arXiv hard-wraps long `<title>` and `<summary>` text across lines, so
     * every extracted string is whitespace-collapsed before use.
     */
    fun parse(xml: String): List<Paper> {
        val out = ArrayList<Paper>()
        var cursor = 0
        while (true) {
            val open = xml.indexOf("<entry>", cursor)
            if (open < 0) break
            val close = xml.indexOf("</entry>", open)
            if (close < 0) break                      // truncated feed — stop, keep what we have
            val entry = xml.substring(open + 7, close)
            cursor = close + 8
            runCatching { parseEntry(entry) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun parseEntry(entry: String): Paper? {
        val title = tag(entry, "title")?.let(::clean).orEmpty()
        if (title.isBlank()) return null              // no title => not a usable result
        val authors = tags(entry, "name").map(::clean).filter { it.isNotBlank() }
        val year = tag(entry, "published")?.trim()?.take(4)?.toIntOrNull()
        return Paper(
            title = title,
            authors = authors,
            year = year,
            abstract = tag(entry, "summary")?.let(::clean).orEmpty(),
            pdfUrl = pdfLink(entry),
            pageUrl = tag(entry, "id")?.let(::clean)?.ifBlank { null },
            venue = null,                             // arXiv preprints have no venue
            source = "arxiv"
        )
    }

    /**
     * The PDF is one `<link/>` among several and its attribute order is not
     * guaranteed; `title="pdf"` is the only stable marker.
     */
    private fun pdfLink(entry: String): String? {
        var i = 0
        while (true) {
            val open = entry.indexOf("<link", i)
            if (open < 0) return null
            val close = entry.indexOf('>', open)
            if (close < 0) return null
            val el = entry.substring(open, close)
            i = close + 1
            if (!el.contains("title=\"pdf\"")) continue
            val h = el.indexOf("href=\"")
            if (h < 0) continue
            val end = el.indexOf('"', h + 6)
            if (end < 0) continue
            return unescape(el.substring(h + 6, end))
        }
    }

    /** Content of the first `<name>`/`<name …>` element, raw (still escaped). */
    private fun tag(entry: String, name: String): String? {
        val open = entry.indexOf("<$name")
        if (open < 0) return null
        val gt = entry.indexOf('>', open)
        if (gt < 0) return null
        if (entry[gt - 1] == '/') return ""           // self-closing, e.g. <summary/>
        val close = entry.indexOf("</$name>", gt)
        if (close < 0) return null
        return entry.substring(gt + 1, close)
    }

    private fun tags(entry: String, name: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            val open = entry.indexOf("<$name>", i)
            if (open < 0) return out
            val close = entry.indexOf("</$name>", open)
            if (close < 0) return out
            out.add(entry.substring(open + name.length + 2, close))
            i = close + 1
        }
    }

    private fun clean(raw: String): String = unescape(raw).replace(WS, " ").trim()

    /** `&amp;` is unescaped LAST so `&amp;lt;` does not become `<`. */
    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private val WS = Regex("\\s+")
}

// ----------------------------------------------------------------------
// Semantic Scholar Graph API
// ----------------------------------------------------------------------

object S2Json {

    /**
     * Parse `/graph/v1/paper/search`. Every field in that response is
     * optional in practice — `abstract`, `venue`, `year`, `openAccessPdf`
     * and `externalIds` are routinely null — so each is probed defensively.
     */
    fun parse(json: String): List<Paper> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        val data = root["data"] as? List<*> ?: return emptyList()
        val out = ArrayList<Paper>()
        for (item in data) {
            runCatching { parseItem(item as? Map<*, *> ?: return@runCatching null) }
                .getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun parseItem(o: Map<*, *>): Paper? {
        val title = (o["title"] as? String)?.trim().orEmpty()
        if (title.isBlank()) return null
        val authors = (o["authors"] as? List<*>).orEmpty().mapNotNull {
            ((it as? Map<*, *>)?.get("name") as? String)?.trim()?.ifBlank { null }
        }
        val externalIds = o["externalIds"] as? Map<*, *>
        val pdf = (o["openAccessPdf"] as? Map<*, *>)?.get("url") as? String
        val arxivId = externalIds?.get("ArXiv") as? String
        val paperId = o["paperId"] as? String
        return Paper(
            title = title,
            authors = authors,
            year = (o["year"] as? Number)?.toInt(),
            abstract = (o["abstract"] as? String)?.trim().orEmpty(),
            pdfUrl = pdf?.ifBlank { null }
                ?: arxivId?.ifBlank { null }?.let { "https://arxiv.org/pdf/$it" },
            pageUrl = paperId?.ifBlank { null }?.let { "https://www.semanticscholar.org/paper/$it" },
            venue = (o["venue"] as? String)?.trim()?.ifBlank { null },
            source = "s2"
        )
    }
}

// ----------------------------------------------------------------------
// Merge + model-facing rendering
// ----------------------------------------------------------------------

/**
 * Union of two result lists, deduped by normalized title.
 *
 * [primary] order is preserved and new [secondary] entries are appended. When
 * both sides carry the same paper we keep whichever has a `pdfUrl` — the
 * agent can only read a paper aloud if it can fetch the PDF — with primary
 * winning ties. Capped at [max].
 */
fun mergePapers(primary: List<Paper>, secondary: List<Paper>, max: Int): List<Paper> {
    if (max <= 0) return emptyList()
    val byTitle = LinkedHashMap<String, Paper>()
    var untitled = 0
    fun key(p: Paper): String {
        val k = p.title.lowercase().filter { it.isLetterOrDigit() }
        return if (k.isNotEmpty()) k else "untitled:${untitled++}"
    }
    for (p in primary) {
        val k = key(p)
        val existing = byTitle[k]
        if (existing == null || (existing.pdfUrl == null && p.pdfUrl != null)) byTitle[k] = p
    }
    for (p in secondary) {
        val k = key(p)
        val existing = byTitle[k]
        // put() on an existing key keeps its original position, which is what
        // we want: an upgraded duplicate must not jump to the end of the list.
        if (existing == null || (existing.pdfUrl == null && p.pdfUrl != null)) byTitle[k] = p
    }
    return byTitle.values.take(max)
}

/**
 * Render results as the compact JSON the model receives via functionResponse.
 *
 * The model pays for every token it reads, so null/empty fields are omitted
 * entirely, the author list is cut to three plus "et al." and abstracts are
 * truncated at a word boundary.
 */
fun papersToJson(papers: List<Paper>, maxAbstractChars: Int = 350): String {
    if (papers.isEmpty()) return "{\"results\":[],\"note\":\"no papers found — try different terms\"}"
    val sb = StringBuilder("{\"results\":[")
    papers.forEachIndexed { i, p ->
        if (i > 0) sb.append(',')
        val fields = ArrayList<String>(7)
        fields.add(field("title", p.title))
        formatAuthors(p.authors)?.let { fields.add(field("authors", it)) }
        p.year?.let { fields.add("\"year\":$it") }
        truncateAtWord(p.abstract, maxAbstractChars).ifBlank { null }
            ?.let { fields.add(field("abstract", it)) }
        p.pdfUrl?.let { fields.add(field("pdf_url", it)) }
        p.pageUrl?.let { fields.add(field("page_url", it)) }
        fields.add(field("source", p.source))
        sb.append('{').append(fields.joinToString(",")).append('}')
    }
    return sb.append("]}").toString()
}

private fun field(name: String, value: String) =
    "\"$name\":\"${GeminiBodyBuilder.escape(value)}\""

/** First three authors; "et al." (no comma) only when more were dropped. */
internal fun formatAuthors(authors: List<String>): String? {
    val named = authors.filter { it.isNotBlank() }
    if (named.isEmpty()) return null
    val head = named.take(3).joinToString(", ")
    return if (named.size > 3) "$head et al." else head
}

/** Cut to [max] chars on the last word boundary and mark the elision. */
internal fun truncateAtWord(text: String, max: Int): String {
    val t = text.trim()
    if (max <= 0) return ""
    if (t.length <= max) return t
    val window = t.substring(0, max)
    val cut = window.lastIndexOf(' ')
    val body = if (cut > 0) window.substring(0, cut) else window
    return body.trimEnd(' ', ',', ';', ':', '.') + "…"
}

// ----------------------------------------------------------------------
// Minimal JSON reader
// ----------------------------------------------------------------------

/**
 * Just enough JSON for the S2 response: objects, arrays, strings (with `\u`
 * escapes), numbers, booleans, null. Exists only because `org.json` is an
 * Android stub under JVM unit tests and we take no new Gradle dependencies.
 */
internal object MiniJson {

    /** Returns Map/List/String/Double/Boolean/null, or null if [src] is not valid JSON. */
    fun parse(src: String): Any? = runCatching {
        val r = Reader(src)
        val v = r.value()
        r.ws()
        v
    }.getOrNull()

    private class Reader(private val s: String) {
        private var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end of input" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        private fun obj(): Map<String, Any?> {
            i++                                      // '{'
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                require(i < s.length && s[i] == ':') { "expected ':'" }
                i++
                m[k] = value()
                ws()
                require(i < s.length) { "unterminated object" }
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++                                      // '['
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                require(i < s.length) { "unterminated array" }
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            require(i < s.length && s[i] == '"') { "expected string" }
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        require(i < s.length) { "dangling escape" }
                        when (val e = s[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                require(i + 4 < s.length) { "short \\u escape" }
                                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)     // \" \\ \/ and anything else literal
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun <T> literal(word: String, v: T): T {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' ||
                        s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            return s.substring(start, i).toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number at $start")
        }
    }
}
