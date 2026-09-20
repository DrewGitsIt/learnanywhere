package com.learnanywhere.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch a URL and strip a little HTML boilerplate. Not a real HTML parser:
     *  - remove <script>/<style>/<noscript> bodies and common layout tags
     *  - keep inline element text
     * Good enough for Wikipedia / news articles; use jsoup if you need fidelity.
     */
    fun fetchText(url: String): String {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", "LearnAnywhere/1.0 (educational reader; contact: dev@example.com)")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val html = resp.body?.string() ?: return ""
            return stripHtml(html)
        }
    }

    private fun stripHtml(html: String): String =
        com.learnanywhere.core.UrlText.stripHtml(html)
}
