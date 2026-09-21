package com.learnanywhere.core

/**
 * Pure (Android-free) building blocks for voice loop v2 (DESIGN #7):
 * streamed model output → spoken sentences.
 *
 *  - [StreamingAnswerExtractor]: our replies are structured JSON
 *    ({"answer": "...", ...}); while it streams we must speak the VALUE of
 *    the answer field, not raw JSON. This incremental extractor consumes
 *    text deltas and emits decoded answer-text deltas. If the stream turns
 *    out not to be JSON (schema fallback → plain prose), it passes text
 *    through untouched.
 *  - [SentenceChunker]: accumulates deltas and emits speakable sentences.
 *
 * Both are unit-tested in LearnAnywherePureTest.
 */

class StreamingAnswerExtractor {
    private enum class Mode { UNDECIDED, JSON_SEEKING_ANSWER, JSON_IN_ANSWER, JSON_DONE, PLAIN }

    private var mode = Mode.UNDECIDED
    private val buf = StringBuilder()      // undecided/seek buffer
    private var escaped = false
    private var unicodeLeft = 0
    private val unicodeBuf = StringBuilder()

    /** Feed a raw delta; returns the answer-text delta to speak/display ("" if none yet). */
    fun feed(delta: String): String {
        val out = StringBuilder()
        for (c in delta) {
            when (mode) {
                Mode.UNDECIDED -> {
                    if (c.isWhitespace() && buf.isEmpty()) continue
                    if (buf.isEmpty() && c != '{') {
                        // Not JSON: plain prose mode, replay nothing lost.
                        mode = Mode.PLAIN
                        out.append(c)
                    } else {
                        buf.append(c)
                        mode = Mode.JSON_SEEKING_ANSWER
                        seek(out)
                    }
                }
                Mode.JSON_SEEKING_ANSWER -> { buf.append(c); seek(out) }
                Mode.JSON_IN_ANSWER -> appendAnswerChar(c, out)
                Mode.JSON_DONE -> { /* swallow the rest of the JSON */ }
                Mode.PLAIN -> out.append(c)
            }
        }
        return out.toString()
    }

    /** True once the whole answer string has been seen (JSON mode only). */
    val answerComplete: Boolean get() = mode == Mode.JSON_DONE

    private fun seek(out: StringBuilder) {
        val marker = "\"answer\""
        val idx = buf.indexOf(marker)
        if (idx < 0) return
        // Find the opening quote of the value after the colon.
        var i = idx + marker.length
        while (i < buf.length && (buf[i].isWhitespace() || buf[i] == ':')) i++
        if (i < buf.length && buf[i] == '"') {
            mode = Mode.JSON_IN_ANSWER
            val rest = buf.substring(i + 1)
            buf.setLength(0)
            for (c in rest) appendAnswerChar(c, out)
        }
    }

    private fun appendAnswerChar(c: Char, out: StringBuilder) {
        if (unicodeLeft > 0) {
            unicodeBuf.append(c)
            if (--unicodeLeft == 0) {
                unicodeBuf.toString().toIntOrNull(16)?.let { out.append(it.toChar()) }
                unicodeBuf.setLength(0)
            }
            return
        }
        if (escaped) {
            escaped = false
            when (c) {
                'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> {}
                '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                'u' -> unicodeLeft = 4
                else -> out.append(c)
            }
            return
        }
        when (c) {
            '\\' -> escaped = true
            '"' -> mode = Mode.JSON_DONE      // closing quote of the answer value
            else -> out.append(c)
        }
    }
}

/**
 * Accumulates text deltas and emits complete sentences for TTS. The first
 * emission is allowed early (short greeting sentences) so speech starts
 * fast; [flush] returns whatever remains at end of stream.
 */
class SentenceChunker(private val maxLen: Int = 400) {
    private val buf = StringBuilder()

    fun feed(delta: String): List<String> {
        buf.append(delta)
        val out = ArrayList<String>()
        while (true) {
            val cut = sentenceEnd() ?: break
            val s = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (s.isNotEmpty()) out.add(s)
        }
        // Pathological no-punctuation stream: don't buffer forever.
        if (buf.length > maxLen) {
            val k = buf.lastIndexOf(" ")
            val cut = if (k > 0) k else buf.length
            val s = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    fun flush(): String? = buf.toString().trim().ifEmpty { null }.also { buf.setLength(0) }

    /** Index just past a sentence terminator followed by space/newline. */
    private fun sentenceEnd(): Int? {
        for (i in 0 until buf.length - 1) {
            val c = buf[i]
            if ((c == '.' || c == '!' || c == '?') && buf[i + 1].isWhitespace()) {
                // Avoid splitting decimals like "3.5 kg" and single initials.
                if (c == '.' && i > 0 && buf[i - 1].isDigit() &&
                    i + 2 < buf.length && buf[i + 2].isDigit()) continue
                return i + 1
            }
            if (c == '\n') return i + 1
        }
        return null
    }
}
