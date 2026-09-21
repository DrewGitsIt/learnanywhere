package com.learnanywhere.data

import android.util.Log

/**
 * Offline PDF text extraction via pdfbox-android.
 *
 * This is what lets the audiobook read a PDF aloud with on-device TTS
 * (Gemini reads the PDF bytes natively for Q&A, but local TTS needs a text
 * layer). Requires [com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init]
 * to have been called once (done in LearnAnywhereApp.onCreate).
 *
 * Returns "" for encrypted, malformed, or scanned (image-only) PDFs — the
 * player already skips empty docs rather than wedging.
 */
object PdfText {

    fun extract(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return try {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
                if (doc.isEncrypted) return ""
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                stripper.sortByPosition = true
                normalize(stripper.getText(doc))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "PDF text extraction failed", t)
            ""
        }
    }

    /**
     * PDF text layers hard-wrap lines mid-sentence and hyphenate across
     * breaks; TTS reads that as choppy nonsense. Re-flow: join hyphenated
     * words, turn single newlines into spaces, keep blank lines (paragraphs).
     */
    fun normalize(raw: String): String =
        raw.replace("\r\n", "\n")
            .replace(Regex("(\\w)-\\n(\\w)"), "$1$2")      // de-hyphenate wraps
            .replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")     // single \n -> space
            .replace(Regex("\\n{2,}"), "\n\n")             // collapse blank runs
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()

    private const val TAG = "PdfText"
}
