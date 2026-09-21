package com.learnanywhere.agent

import com.learnanywhere.core.TavilySearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * `search_web` agent tool: general web search via Tavily, an LLM-oriented
 * search API with a free tier. Gemini's built-in google_search grounding has
 * zero quota on the free tier, so this is the app's only web-search backend.
 *
 * Tool-result convention: always return a JSON string to hand back to the
 * model, success or `{"error":"..."}` — never throw.
 */
class WebSearchTool(private val key: () -> String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, maxResults: Int = 5): String {
        val apiKey = key()
        if (apiKey.isBlank()) {
            return """{"error":"web search is not configured — add a Tavily API key in Settings (free at tavily.com)"}"""
        }
        if (query.isBlank()) {
            return """{"error":"query must not be empty"}"""
        }
        val clamped = maxResults.coerceIn(1, 10)

        return withContext(Dispatchers.IO) {
            try {
                val reqBody = TavilySearch.buildRequestBody(query, clamped)
                val req = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create("application/json".toMediaType(), reqBody.toByteArray(Charsets.UTF_8)))
                    .build()

                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        """{"error":"${escape(TavilySearch.summarizeError(resp.code, respBody))}"}"""
                    } else {
                        TavilySearch.parseResponse(respBody)
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: IOException) {
                """{"error":"web search failed: ${escape(e.message ?: "network error")}"}"""
            } catch (t: Throwable) {
                """{"error":"web search failed: ${escape(t.message ?: "unknown error")}"}"""
            }
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
