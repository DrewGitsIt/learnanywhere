package com.learnanywhere.agent

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-build transcript log (DESIGN.md §3.7): every Gemini request/response
 * appended as JSONL under filesDir/transcripts/. This is the debugger and the
 * future eval corpus. No-ops unless [dir] is set (LearnAnywhereApp sets it
 * only for debuggable builds); payloads are truncated so inline PDF base64
 * doesn't fill the disk. Pure java.io — safe to reference from JVM tests.
 */
object TranscriptLog {
    @Volatile var dir: File? = null

    private const val MAX_PAYLOAD = 20_000

    fun log(kind: String, payload: String) {
        val d = dir ?: return
        try {
            d.mkdirs()
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val line = org.json.JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("kind", kind)
                .put("payload", if (payload.length <= MAX_PAYLOAD) payload
                                else payload.take(MAX_PAYLOAD) + "…[truncated ${payload.length} chars]")
                .toString()
            File(d, "gemini-$day.jsonl").appendText(line + "\n")
        } catch (_: Throwable) {
            // Logging must never break the app.
        }
    }
}
