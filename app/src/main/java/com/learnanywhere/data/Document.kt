package com.learnanywhere.data

/**
 * In-app "document" abstraction. A document can be any of:
 *  - a local PDF (path stored; bytes read at use-time from a SAF content:// URI or file)
 *  - a text article pasted by the user
 *  - a URL (e.g. Wikipedia) whose text has been fetched + cached
 *
 * `source` drives how the [DocumentStore] loads/extracts content and how
 * the Gemini caller encodes it (inline_data PDF vs. in-context text).
 */
data class Document(
    val id: String,
    val title: String,
    val source: Source,
    /** When source == PDF, this is a persisted reference (content:// or file://) or an absolute path. */
    val pdfLocator: String?,
    /** Extracted plain-text body (from PDF text layer OR from a fetched URL). Kept in memory + optionally on disk. */
    val text: String,
    /** Human-friendly provenance (URL, file name, etc.) */
    val provenance: String,
    /** Optional: list of embedded figures (image bytes or URLs) the UI can render. Empty for now. */
    var figures: List<Figure> = emptyList(),
    /** Order hint for audiobook playback. */
    var order: Int = 0
) {
    enum class Source { PDF, TEXT, URL }

    val isPdf: Boolean get() = source == Source.PDF && !pdfLocator.isNullOrBlank()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Document) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * A figure we want to display in the agent discourse view and, when parked,
 * in Android Auto. In this MVP we synthesize "figures" from extracted PDF
 * page images (via Android's PdfDocument renderer) and article <img> tags.
 */
data class Figure(
    val id: String,
    val title: String,
    val caption: String?,
    val mimeType: String,          // "image/png" / "image/jpeg"
    val bytes: ByteArray,
    val source: SourceRef,
    val isFigureCandidate: Boolean = false
) {
    enum class SourceRef { PDF_PAGE, ARTICLE_IMG, AGENT_ATTACHMENT }
    override fun equals(other: Any?): Boolean = other is Figure && id == other.id
    override fun hashCode(): Int = id.hashCode()
}
