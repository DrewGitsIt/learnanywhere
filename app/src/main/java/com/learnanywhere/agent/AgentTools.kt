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
 */
class AgentTools(private val store: DocumentStore) {

    /** Emitted verbatim into tools[].functionDeclarations. */
    val declarationsJson: String = """[
      {"name":"download_document",
       "description":"Download a document from a public URL and add it to the user's library so it can be discussed and read aloud. Use when the user asks to find, fetch, or add a paper or article, or confirms they want one that web search surfaced. Prefer direct PDF links (for arXiv use https://arxiv.org/pdf/<id>). Do not use for a page the user only wants summarized in passing — answer from search instead.",
       "parameters":{"type":"object","properties":{
         "url":{"type":"string","description":"Absolute https URL of the PDF or article to download"},
         "title":{"type":"string","description":"Short human-readable title for the library entry"}},
         "required":["url","title"]}},
      {"name":"list_library",
       "description":"List the documents currently in the user's library (title and kind). Use to check whether a document is already present before downloading it again.",
       "parameters":{"type":"object","properties":{}}}
    ]""".trimIndent()

    /** Execute one call; always returns a JSON object string for functionResponse. */
    suspend fun execute(name: String, argsJson: String): String {
        return try {
            when (name) {
                "list_library" -> listLibrary()
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
}
