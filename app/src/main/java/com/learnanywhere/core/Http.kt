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

    /**
     * Fetch raw bytes + Content-Type (for the download_document tool).
     * Capped at [maxBytes] so a bad link can't fill memory.
     */
    fun fetchBytes(url: String, maxBytes: Long = 30L * 1024 * 1024): Pair<ByteArray, String?> {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", "LearnAnywhere/1.0 (educational reader)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("empty body")
            val len = body.contentLength()
            if (len > maxBytes) throw java.io.IOException("file too large (${len / 1024 / 1024} MB)")
            val bytes = body.byteStream().use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw java.io.IOException("file too large (>${maxBytes / 1024 / 1024} MB)")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            return bytes to resp.header("Content-Type")
        }
    }
}
