package com.learnanywhere.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.UUID

/**
 * In-memory collection of [Document]s the user has added.
 *
 *  - PDFs come from a Storage Access Framework `content://` URI. On add, we
 *    render every page to a [Figure] and compute a "figure candidate" score.
 *  - Text docs are pasted strings.
 *  - URL docs are fetched (plain text) for grounding.
 *
 * Persistence: backed by [app.db]. On add we write a [DocumentRow] to Room and
 * persist the PDF bytes to the app's internal storage; on cold start the
 * [reload] hook rehydrates them.
 */
class DocumentStore(
    private val context: Context
) {

    private val _docs = LinkedHashMap<String, Document>()

    val docs: List<Document> get() = _docs.values.toList()
    val size: Int get() = _docs.size

    /** Set at add-time by [LearnAnywhereApp]; writes bytes + DB row. */
    var onPdfAdded: ((Document) -> Unit)? = null
    var onTextOrUrlAdded: ((Document) -> Unit)? = null
    var onRemoved: ((String) -> Unit)? = null

    // ------------------------------------------------------------------
    // Add
    // ------------------------------------------------------------------

    suspend fun addPdf(uri: Uri): Result<Document> = runCatching {
        ensureReadPermission(uri)
        val bytes = readAllBytes(uri)
        val title = guessTitle(uri)

        // Extract figures (page bitmaps) + per-page "figure candidate" score.
        val pages = com.learnanywhere.data.PdfFigureExtractor.extract(bytes, title, quality = 65)
        val figures = pages.map { p ->
            Figure(
                id = UUID.randomUUID().toString(),
                title = p.title,
                caption = p.caption,
                mimeType = p.mime,
                bytes = p.bytes,
                source = Figure.SourceRef.PDF_PAGE,
                isFigureCandidate = p.isFigureCandidate
            )
        }
        // Offline text layer — powers TTS playback of the PDF. (Gemini reads
        // the PDF bytes natively for Q&A regardless.)
        val text = PdfText.extract(bytes)

        val doc = Document(
            id = UUID.randomUUID().toString(),
            title = title,
            source = Document.Source.PDF,
            pdfLocator = uri.toString(),
            text = text,
            provenance = uri.toString(),
            figures = figures,
            order = _docs.size
        )
        _docs[doc.id] = doc
        onPdfAdded?.invoke(doc)
        doc
    }

    suspend fun addText(title: String, text: String, provenance: String = "pasted-text"): Document {
        val doc = Document(
            id = UUID.randomUUID().toString(),
            title = title,
            source = Document.Source.TEXT,
            pdfLocator = null,
            text = text,
            provenance = provenance,
            order = _docs.size
        )
        _docs[doc.id] = doc
        onTextOrUrlAdded?.invoke(doc)
        return doc
    }

    suspend fun addUrl(url: String, title: String): Result<Document> = runCatching {
        val fetched = com.learnanywhere.core.Http.fetchText(url)
        val doc = Document(
            id = UUID.randomUUID().toString(),
            title = title,
            source = Document.Source.URL,
            pdfLocator = null,
            text = fetched,
            provenance = url,
            order = _docs.size
        )
        _docs[doc.id] = doc
        onTextOrUrlAdded?.invoke(doc)
        doc
    }

    /**
     * Add a PDF the agent downloaded (bytes in hand, no SAF URI). Bytes are
     * written under filesDir/downloads/ so the standard persistence path
     * ([LearnAnywhereApp.persistPdf] reads the locator) copies them into the
     * byte store like any other PDF.
     */
    suspend fun addDownloadedPdf(title: String, bytes: ByteArray, sourceUrl: String): Result<Document> = runCatching {
        val dir = java.io.File(context.filesDir, "downloads").apply { mkdirs() }
        val f = java.io.File(dir, UUID.randomUUID().toString() + ".pdf")
        f.writeBytes(bytes)
        val pages = PdfFigureExtractor.extract(bytes, title, quality = 65)
        val figures = pages.map { p ->
            Figure(
                id = UUID.randomUUID().toString(),
                title = p.title,
                caption = p.caption,
                mimeType = p.mime,
                bytes = p.bytes,
                source = Figure.SourceRef.PDF_PAGE,
                isFigureCandidate = p.isFigureCandidate
            )
        }
        val doc = Document(
            id = UUID.randomUUID().toString(),
            title = title,
            source = Document.Source.PDF,
            pdfLocator = "file://" + f.absolutePath,
            text = PdfText.extract(bytes),
            provenance = sourceUrl,
            figures = figures,
            order = _docs.size
        )
        _docs[doc.id] = doc
        onPdfAdded?.invoke(doc)
        doc
    }

    fun remove(id: String) {
        _docs.remove(id)
        onRemoved?.invoke(id)
    }

    fun byId(id: String): Document? = _docs[id]
    fun selectedIds(selector: (Document) -> Boolean): List<String> =
        _docs.values.filter(selector).map { it.id }

    /**
     * Rehydrate from a [DocumentRow]-shaped view model (see app.db). Used at
     * cold start by [LearnAnywhereApp] to restore the user's library.
     */
    internal fun hydrate(docs: List<Document>) {
        _docs.clear()
        docs.forEach { _docs[it.id] = it }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun ensureReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "could not persist read permission for $uri", e)
        }
    }

    private fun readAllBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("cannot read $uri")

    private fun guessTitle(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "document.pdf"
    }

    companion object {
        private const val TAG = "DocumentStore"
    }
}
