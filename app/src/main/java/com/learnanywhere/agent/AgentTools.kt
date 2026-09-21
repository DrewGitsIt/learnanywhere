package com.learnanywhere.agent

import com.learnanywhere.core.Http
import com.learnanywhere.core.UrlText
import com.learnanywhere.data.DocumentStore

/**
 * The agent's custom tools (DESIGN.md roadmap #6). Declarations follow the
 * tool-schema playbook in §3.7: few tools, verb_noun names, descriptions
 * that say when NOT to use them, compact structured results.
 *
 * Results and errors are returned to the MODEL as functionResponse JSON —
 * a failed download is information for the model to act on (retry another
 * URL, tell the user), never an exception up to the UI.
 *
 * Search tools exist because Gemini's google_search grounding has zero
 * quota on the free tier (DESIGN §5): search_papers is keyless (arXiv +
 * Semantic Scholar); search_web needs a Tavily key and is only DECLARED
 * when one is configured and web search is enabled — the model never sees
 * a tool that cannot succeed.
 */
class AgentTools(
    private val store: DocumentStore,
    private val tavilyKey: () -> String = { "" },
    private val webEnabled: () -> Boolean = { true },
) {

    private val papers = PaperSearchTool()
    private val web = WebSearchTool(tavilyKey)

    /** Emitted verbatim into tools[].functionDeclarations. Recomputed per
     *  request: the Tavily key and web-search toggle can change at runtime. */
    val declarationsJson: String
        get() = buildString {
            append("[\n")
            append(SEARCH_PAPERS_DECL).append(",\n")
            if (webEnabled() && tavilyKey().isNotBlank()) {
                append(SEARCH_WEB_DECL).append(",\n")
            }
            append(DOWNLOAD_DOCUMENT_DECL).append(",\n")
            append(LIST_LIBRARY_DECL).append("\n]")
        }

    /** Execute one call; always returns a JSON object string for functionResponse. */
    suspend fun execute(name: String, argsJson: String): String {
        return try {
            when (name) {
                "list_library" -> listLibrary()
                "search_papers" -> {
                    val args = org.json.JSONObject(argsJson)
                    papers.search(args.getString("query"), args.optInt("max_results", 5))
                }
                "search_web" -> {
                    val args = org.json.JSONObject(argsJson)
                    web.search(args.getString("query"), args.optInt("max_results", 5))
                }
                "download_document" -> {
                    val args = org.json.JSONObject(argsJson)
                    downloadDocument(args.getString("url"), args.getString("title"))
                }
                else -> error("unknown tool: $name")
            }
        } catch (t: Throwable) {
            org.json.JSONObject().put("error", t.message ?: t.toString()).toString()
        }
    }

    private fun listLibrary(): String {
        val docs = store.docs
        val arr = org.json.JSONArray()
        docs.take(20).forEach { d ->
            arr.put(org.json.JSONObject()
                .put("title", d.title)
                .put("kind", d.source.name.lowercase())
                .put("has_text", d.text.isNotBlank()))
        }
        return org.json.JSONObject()
            .put("documents", arr)
            .put("total", docs.size)
            .toString()
    }

    private suspend fun downloadDocument(url: String, title: String): String {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "url must be an absolute http(s) URL"
        }
        val (bytes, contentType) = Http.fetchBytes(url)
        val isPdf = (contentType?.contains("pdf", ignoreCase = true) == true) ||
                (bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte()
                        && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte())
        return if (isPdf) {
            val doc = store.addDownloadedPdf(title, bytes, sourceUrl = url).getOrThrow()
            org.json.JSONObject()
                .put("status", "added")
                .put("kind", "pdf")
                .put("title", doc.title)
                .put("pages", doc.figures.size)
                .put("has_text_for_audio", doc.text.isNotBlank())
                .toString()
        } else {
            val text = UrlText.stripHtml(String(bytes, Charsets.UTF_8))
            require(text.isNotBlank()) { "no readable text at that URL" }
            val doc = store.addText(title, text, provenance = url)
            org.json.JSONObject()
                .put("status", "added")
                .put("kind", "article")
                .put("title", doc.title)
                .put("chars", text.length)
                .toString()
        }
    }

    companion object {
        private val SEARCH_PAPERS_DECL = """
      {"name":"search_papers",
       "description":"Search for academic papers by title, topic, or author (arXiv + Semantic Scholar, no key needed). Results include pdf_url when a free PDF exists — pass that to download_document to add the paper to the library. Use when the user wants to find, look up, or fetch a paper; do not use for general facts or news (use search_web) or for papers already in the library (check list_library).",
       "parameters":{"type":"object","properties":{
         "query":{"type":"string","description":"Title, topic, or author terms — a paper title works best verbatim"},
         "max_results":{"type":"integer","description":"How many candidates to return, 1-10 (default 5)"}},
         "required":["query"]}}""".trimIndent()

        private val SEARCH_WEB_DECL = """
      {"name":"search_web",
       "description":"Search the web for current or general information (Tavily). Returns a short answer plus result snippets with URLs. Use for facts, news, or context not in the attached documents; do not use to find academic papers — search_papers is better for those.",
       "parameters":{"type":"object","properties":{
         "query":{"type":"string","description":"Plain search query"},
         "max_results":{"type":"integer","description":"How many results, 1-10 (default 5)"}},
         "required":["query"]}}""".trimIndent()

        private val DOWNLOAD_DOCUMENT_DECL = """
      {"name":"download_document",
       "description":"Download a document from a public URL and add it to the user's library so it can be discussed and read aloud. Use when the user asks to find, fetch, or add a paper or article, or confirms they want one that search surfaced. Prefer direct PDF links (search_papers results carry pdf_url; for arXiv use https://arxiv.org/pdf/<id>). Do not use for a page the user only wants summarized in passing — answer from search instead.",
       "parameters":{"type":"object","properties":{
         "url":{"type":"string","description":"Absolute https URL of the PDF or article to download"},
         "title":{"type":"string","description":"Short human-readable title for the library entry"}},
         "required":["url","title"]}}""".trimIndent()

        private val LIST_LIBRARY_DECL = """
      {"name":"list_library",
       "description":"List the documents currently in the user's library (title and kind). Use to check whether a document is already present before downloading it again.",
       "parameters":{"type":"object","properties":{}}}""".trimIndent()
    }
}
