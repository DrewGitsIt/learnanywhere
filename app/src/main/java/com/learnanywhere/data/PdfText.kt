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

    /** Page-split text: [text] is the pages joined, [pageOffsets] indexes it. */
    data class Paged(val text: String, val pageOffsets: List<Int>)

    /**
     * Same text, split per page, for mapping a passage back to the page it
     * came from ([com.learnanywhere.core.PageMap]).
     *
     * NOT a replacement for [extract]: normalizing page-by-page differs from
     * normalizing the whole document (cross-page hyphenation, whitespace), and
     * the stored document text must stay byte-identical to what [extract]
     * produced or the grounding prefix stops hitting Gemini's implicit cache
     * (DESIGN §5). Callers match across the two with PageMap, never by equality.
     *
     * `pageOffsets[i]` is where page i+1 starts in [Paged.text]. Empty when the
     * PDF is encrypted, malformed, or has no text layer.
     */
    fun extractPaged(bytes: ByteArray): Paged {
        if (bytes.isEmpty()) return Paged("", emptyList())
        return try {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
                if (doc.isEncrypted) return Paged("", emptyList())
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                stripper.sortByPosition = true
                val sb = StringBuilder()
                val offsets = ArrayList<Int>(doc.numberOfPages)
                for (page in 1..doc.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    if (page > 1) sb.append(PAGE_SEPARATOR)
                    offsets.add(sb.length)
                    sb.append(normalize(stripper.getText(doc)))
                }
                Paged(sb.toString(), offsets)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "paged PDF text extraction failed", t)
            Paged("", emptyList())
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

    /** Paragraph break, so a page boundary reads like one to TTS and to PageMap. */
    internal const val PAGE_SEPARATOR = "\n\n"

    private const val TAG = "PdfText"
}
