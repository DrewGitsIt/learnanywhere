package com.learnanywhere.agent

import com.learnanywhere.core.ArxivAtom
import com.learnanywhere.core.Paper
import com.learnanywhere.core.S2Json
import com.learnanywhere.core.mergePapers
import com.learnanywhere.core.papersToJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Network half of the `search_papers` agent tool.
 *
 * Why our own search instead of Gemini's `google_search` grounding: the free
 * tier has zero grounding quota, so every grounded call fails. arXiv and
 * Semantic Scholar are both keyless and free, which keeps the whole app on
 * free tiers (DESIGN §3.7).
 *
 * The two sources are not equals:
 *  - **arXiv is primary.** Reliable, no rate limiting we have hit, and its
 *    PDFs are directly fetchable — which is what lets the agent go on to read
 *    a paper aloud.
 *  - **Semantic Scholar is best-effort.** The anonymous pool 429s frequently;
 *    that is normal operation, not an error. Any S2 failure is swallowed and
 *    arXiv-only results are still a success (and vice versa).
 *
 * Both queries run concurrently and the timeouts are short: a hands-free user
 * waiting in silence would rather have partial results than a hung turn.
 *
 * All results are JSON strings destined for a `functionResponse` part — this
 * class never throws at the UI. Failures come back as `{"error": "..."}`,
 * which the model reads and turns into an apology.
 */
class PaperSearchTool(private val client: OkHttpClient = defaultClient()) {

    suspend fun search(query: String, maxResults: Int = 5): String {
        val q = query.trim()
        if (q.isBlank()) return """{"error":"query must not be empty"}"""
        val max = maxResults.coerceIn(1, 10)

        // Not runCatching: it would swallow CancellationException and keep
        // searching for a turn nobody is waiting on.
        suspend fun attempt(block: () -> List<Paper>): Result<List<Paper>> =
            try { Result.success(block()) }
            catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (t: Throwable) { Result.failure(t) }

        val (arxiv, s2) = coroutineScope {
            val a = async(Dispatchers.IO) { attempt { fetchArxiv(q) } }
            val b = async(Dispatchers.IO) { attempt { fetchS2(q) } }
            a.await() to b.await()
        }

        val arxivPapers = arxiv.getOrNull()
        val s2Papers = s2.getOrNull()
        if (arxivPapers == null && s2Papers == null) {
            val reason = oneLine(arxiv.exceptionOrNull() ?: s2.exceptionOrNull())
            return """{"error":"paper search unavailable: ${escape(reason)}"}"""
        }
        return papersToJson(mergePapers(arxivPapers.orEmpty(), s2Papers.orEmpty(), max))
    }

    // ------------------------------------------------------------------

    /**
     * arXiv wants the phrase quoted (`all:"…"`), otherwise every term is
     * OR-ed and the top hits drift off topic. We over-fetch (8) so the merge
     * still has something to dedupe against before the cap is applied.
     */
    private fun fetchArxiv(query: String): List<Paper> {
        val url = "https://export.arxiv.org/api/query?search_query=all:%22" +
                enc(query) + "%22&start=0&max_results=8"
        return ArxivAtom.parse(get(url))
    }

    private fun fetchS2(query: String): List<Paper> {
        val url = "https://api.semanticscholar.org/graph/v1/paper/search?query=" +
                enc(query) + "&limit=8&fields=" +
                "title,year,abstract,authors,externalIds,openAccessPdf,venue"
        return S2Json.parse(get(url))
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Keep the model's error short and readable — never a stack trace. */
    private fun oneLine(t: Throwable?): String {
        val msg = t?.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val text = msg.ifBlank { t?.javaClass?.simpleName ?: "unknown error" }
        return if (text.length > 120) text.take(117) + "..." else text
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val USER_AGENT = "LearnAnywhere/1.0 (educational reader)"

        /**
         * Deliberately not [com.learnanywhere.core.Http]'s client: that one
         * allows a 45 s read for long article fetches. A search behind a
         * voice turn must fail fast.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
