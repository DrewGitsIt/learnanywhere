package com.learnanywhere

import com.learnanywhere.core.ToolWire
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Audit hardening (2026-09-21): tool-loop wire format helpers. */
class ToolWireTest {

    // ---- functionResponsePart ----

    @Test
    fun functionResponseEscapesNewlinesAndQuotesInResult() {
        // The exact live failure mode: an HTML error page with newlines
        // spliced raw into the request would 400 the whole turn.
        val result = JSONObject().put("error", "web search failed: HTTP 502 (<html>\n<body>\"Bad Gateway\"</body>)").toString()
        val part = ToolWire.functionResponsePart("call_1", "search_web", result)
        val parsed = JSONObject(part)                       // must be valid JSON
        val fr = parsed.getJSONObject("functionResponse")
        assertEquals("call_1", fr.getString("id"))
        assertEquals("search_web", fr.getString("name"))
        assertTrue(fr.getJSONObject("response").getString("error").contains("Bad Gateway"))
    }

    @Test
    fun functionResponseReplacesMalformedResultInsteadOfBreakingTheTurn() {
        val part = ToolWire.functionResponsePart(null, "search_web", "<html>oops</html>")
        val fr = JSONObject(part).getJSONObject("functionResponse")
        assertFalse(fr.has("id"))
        assertEquals("tool returned malformed output",
            fr.getJSONObject("response").getString("error"))
    }

    @Test
    fun functionResponseEscapesHostileToolName() {
        val part = ToolWire.functionResponsePart("x\"y", "na\"me\n", "{\"ok\":true}")
        val fr = JSONObject(part).getJSONObject("functionResponse")   // parses = escaped
        assertEquals("na\"me\n", fr.getString("name"))
        assertEquals("x\"y", fr.getString("id"))
    }

    // ---- coalesceTextParts ----

    @Test
    fun coalesceMergesStreamedTextFragmentsKeepingSignatureAndCallOrder() {
        val parts = listOf(
            """{"text":"Hello "}""",
            """{"text":"world","thoughtSignature":"SIG1"}""",
            """{"text":"!"}""",
            """{"functionCall":{"name":"search_papers","args":{"query":"bert"},"id":"c1"},"thoughtSignature":"SIG2"}"""
        )
        val out = ToolWire.coalesceTextParts(parts)
        assertEquals(2, out.size)
        val text = JSONObject(out[0])
        assertEquals("Hello world!", text.getString("text"))
        assertEquals("SIG1", text.getString("thoughtSignature"))
        val fc = JSONObject(out[1])
        assertEquals("search_papers", fc.getJSONObject("functionCall").getString("name"))
        assertEquals("SIG2", fc.getString("thoughtSignature"))
    }

    @Test
    fun coalesceLeavesNonStreamedShapesAlone() {
        val single = listOf("""{"text":"complete answer","thoughtSignature":"S"}""")
        assertEquals(single, ToolWire.coalesceTextParts(single))
        val callOnly = listOf("""{"functionCall":{"name":"list_library","args":{}}}""")
        assertEquals(callOnly, ToolWire.coalesceTextParts(callOnly))
    }

    @Test
    fun coalesceNeverMergesThoughtParts() {
        val parts = listOf(
            """{"text":"thinking...","thought":true}""",
            """{"text":"real "}""",
            """{"text":"answer"}"""
        )
        val out = ToolWire.coalesceTextParts(parts)
        assertEquals(2, out.size)
        assertTrue(JSONObject(out[0]).optBoolean("thought"))
        assertEquals("real answer", JSONObject(out[1]).getString("text"))
    }

    // ---- isPrivateHost ----

    @Test
    fun privateHostsAreRejectedPublicOnesAreNot() {
        listOf("localhost", "router.local", "127.0.0.1", "10.0.0.5", "192.168.1.1",
            "172.16.0.1", "172.31.255.255", "169.254.10.10", "0.0.0.0", "::1",
            "fe80:1234::1", "printer.internal").forEach {
            assertTrue(ToolWire.isPrivateHost(it), "expected private: $it")
        }
        listOf("arxiv.org", "export.arxiv.org", "172.32.0.1", "192.169.1.1",
            "11.1.1.1", "api.tavily.com").forEach {
            assertFalse(ToolWire.isPrivateHost(it), "expected public: $it")
        }
    }
}
