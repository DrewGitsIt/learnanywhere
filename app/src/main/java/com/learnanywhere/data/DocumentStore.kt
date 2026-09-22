package com.learnanywhere.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.sync.withLock
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

    /**
     * Set at startup by [LearnAnywhereApp]: doc id -> per-page text, read back
     * from the stored PDF bytes. Lives here rather than on [Document] because
     * page offsets are derived data — never persisted, recomputed on demand.
     */
    var pagedTextProvider: (suspend (String) -> PdfText.Paged?)? = null

    private val pagedText = java.util.concurrent.ConcurrentHashMap<String, PdfText.Paged>()
    private val pagedTextLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Skip-map plumbing (DESIGN §7.2), wired at startup by [LearnAnywhereApp]
     * the same way [pagedTextProvider] is. Both null is a valid configuration:
     * [skipRangesFor] then falls back to the offline heuristics and simply
     * recomputes them per process.
     */
    var skipStore: SkipStore? = null
    var boilerplateClassifier: BoilerplateClassifier? = null

    private val skipRanges = java.util.concurrent.ConcurrentHashMap<String, List<IntRange>>()
    private val skipLock = kotlinx.coroutines.sync.Mutex()

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
        pagedText.remove(id)
        skipRanges.remove(id)
        skipStore?.remove(id)
        onRemoved?.invoke(id)
    }

    // ------------------------------------------------------------------
    // Page mapping (DESIGN §6.5)
    // ------------------------------------------------------------------

    /**
     * Per-page text for a PDF, memoized for the process lifetime. Null for
     * non-PDF docs, when no provider is wired, or when the PDF has no text
     * layer. Never throws — a missing page render must not break an ask.
     *
     * A text-less result is memoized too: re-parsing a scanned 100-page PDF on
     * every read-along section is the jank this cache exists to prevent. A
     * provider *failure* (I/O) is not cached, so it retries.
     */
    suspend fun pagedTextFor(id: String): PdfText.Paged? {
        val doc = _docs[id] ?: return null
        if (doc.source != Document.Source.PDF) return null
        pagedText[id]?.let { return it.takeIf { p -> p.pageOffsets.isNotEmpty() } }
        val provider = pagedTextProvider ?: return null
        // One extraction at a time: the reply citation and the read-along
        // cursor ask for the same document at the same moment.
        return pagedTextLock.withLock {
            pagedText[id]?.let { return@withLock it.takeIf { p -> p.pageOffsets.isNotEmpty() } }
            val paged = try {
                provider(id)
            } catch (t: Throwable) {
                Log.w(TAG, "paged text extraction failed for $id", t)
                null
            } ?: return@withLock null
            pagedText[id] = paged
            paged.takeIf { it.pageOffsets.isNotEmpty() }
        }
    }

    // ------------------------------------------------------------------
    // Boilerplate skip-map (DESIGN §7.2)
    // ------------------------------------------------------------------

    /**
     * The char ranges of [id]'s text that playback and read-with-me pass over
     * silently. Empty when the document is gone, text-less, or genuinely clean.
     *
     * Memoized per process and persisted as a sidecar, because classification
     * is one network call and a section boundary asks for it every few seconds.
     * Lookup order: memory -> [SkipStore] -> classify.
     *
     * A classifier failure still yields the offline heuristics — but that
     * result is NOT written to the sidecar, so a cold start after the quota
     * resets gets the LLM pass it missed. Never throws: boilerplate is a
     * nicety, and no failure here may stop a document being read.
     */
    suspend fun skipRangesFor(id: String): List<IntRange> {
        skipRanges[id]?.let { return it }
        val doc = _docs[id] ?: return emptyList()
        if (doc.text.isBlank()) return emptyList()
        return skipLock.withLock {
            skipRanges[id]?.let { return@withLock it }
            skipStore?.load(id)?.let { saved ->
                skipRanges[id] = saved
                return@withLock saved
            }
            val classifier = boilerplateClassifier
            var durable = true
            val ranges = try {
                // classifyOrNull is null when the LLM pass didn't run (no
                // key, quota 429, transport error): use heuristics for now
                // but don't persist them, so a later cold start retries.
                val llmResult = classifier?.classifyOrNull(doc.text)
                if (llmResult != null) llmResult else {
                    durable = false
                    com.learnanywhere.core.Boilerplate.heuristicRanges(doc.text)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "boilerplate classification failed for $id", t)
                durable = false
                try {
                    com.learnanywhere.core.Boilerplate.heuristicRanges(doc.text)
                } catch (t2: Throwable) {
                    emptyList()
                }
            }
            skipRanges[id] = ranges
            if (durable) skipStore?.save(id, ranges)
            ranges
        }
    }

    /**
     * The skip-map [skipRangesFor] has ALREADY produced for [id] — memory
     * first, sidecar second — or empty when none exists yet. Never classifies,
     * never touches the network, never suspends: this is the path playback and
     * read-with-me take on a tap, and the first tap must not wait on Gemini.
     * Warming at add-time/startup ([LearnAnywhereApp]) is what makes the
     * sidecar hot by the time anyone presses play.
     */
    fun cachedSkipRanges(id: String): List<IntRange> {
        skipRanges[id]?.let { return it }
        val saved = skipStore?.load(id) ?: return emptyList()
        skipRanges[id] = saved
        return saved
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
        pagedText.keys.retainAll(_docs.keys)
        skipRanges.keys.retainAll(_docs.keys)
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
