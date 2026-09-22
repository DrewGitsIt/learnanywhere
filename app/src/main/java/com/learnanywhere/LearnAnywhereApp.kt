package com.learnanywhere

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import com.learnanywhere.agent.LearnAnywhereAgent
import com.learnanywhere.app.db.AppDatabase
import com.learnanywhere.app.db.DocumentRow
import com.learnanywhere.data.Document
import com.learnanywhere.data.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application entry: wires the DocumentStore, LearnAnywhereAgent, and AppDatabase.
 *
 * On cold start:
 *  1. [app.db.documents().observe()] fires the initial value (all rows).
 *  2. For each row: re-attach figures from [AppPdfStore.figuresFor], rebuild a
 *     Document, and [DocumentStore.hydrate].
 *  3. Subscribe to the Room flow so any write elsewhere (another activity)
 *     refreshes the in-memory store.
 *
 * Why not Hilt? Same reason as before: one-activity app, hand-rolled DI keeps
 * the code readable and the build tight.
 */
class LearnAnywhereApp : Application() {

    lateinit var db: AppDatabase
        private set
    lateinit var store: DocumentStore
        private set
    lateinit var agent: LearnAnywhereAgent
        private set
    lateinit var prefs: SharedPreferences
        private set
    lateinit var ui: com.learnanywhere.ui.UiController
        private set

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    override fun onCreate() {
        super.onCreate()
        instance = this

        // pdfbox-android needs its resources loaded once before any PDF parse.
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)

        // Debug builds keep a Gemini request/response transcript (JSONL) —
        // the debugger and future eval corpus (DESIGN.md §3.7).
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            com.learnanywhere.agent.TranscriptLog.dir = java.io.File(filesDir, "transcripts")
        }

        prefs = getSharedPreferences("learnanywhere", MODE_PRIVATE)
        db = AppDatabase(this)
        store = DocumentStore(this)
        agent = LearnAnywhereAgent(
            api = { prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty() },
            model = { prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL).orEmpty() },
            docs = { store.docs },
            appCtx = this,
            webSearch = { prefs.getBoolean(KEY_WEB_SEARCH, true) },
            tools = com.learnanywhere.agent.AgentTools(
                store = store,
                tavilyKey = { prefs.getString(KEY_TAVILY_API_KEY, "").orEmpty() },
                webEnabled = { prefs.getBoolean(KEY_WEB_SEARCH, true) }
            )
        )
        ui = com.learnanywhere.ui.UiController(this)

        // ---- boilerplate skip-map (DESIGN §7.2) ----
        // Sidecar files, never a column on the document: the stored text must
        // stay byte-identical for the prompt cache (§5).
        store.skipStore = com.learnanywhere.data.SkipStore(java.io.File(filesDir, "skipmaps"))
        // One plain-text Gemini call per document, no schema and no tools.
        // Null when there is no key — classifyOrNull reads that as "the LLM
        // pass didn't run" and falls back to heuristics WITHOUT persisting
        // them, so a later start retries. Exceptions deliberately propagate
        // into the same path.
        store.boilerplateClassifier = com.learnanywhere.data.BoilerplateClassifier { prompt ->
            val key = prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty()
            if (key.isBlank()) null
            else com.learnanywhere.agent.Gemini(
                { key },
                { prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL).orEmpty() }
            ).generateText(
                contents = listOf(com.learnanywhere.agent.Gemini.Message(
                    "user", listOf(com.learnanywhere.agent.Gemini.Part(text = prompt)))),
                maxTokens = 2048,
                thinkingLevel = "low"
            ).text
        }
        // Plain audiobook playback speaks the kept text (DESIGN §7.2). Cached
        // only — see DocumentStore.cachedSkipRanges — and never blank-out:
        // [AudiobookPlayer] falls back to the full text.
        ui.player.speakableText = { d ->
            com.learnanywhere.core.Boilerplate.keptText(d.text, store.cachedSkipRanges(d.id))
        }

        // ---- persistence plumbing (Document ↔ Room + [AppPdfStore]) ----
        store.onPdfAdded = { doc -> persistPdf(doc); warmSkipMap(doc) }
        store.onTextOrUrlAdded = { doc -> persistRow(doc); warmSkipMap(doc) }
        // Page offsets for "figures ride along" (DESIGN §6.5). Derived, never
        // persisted: re-extracted from the stored bytes and cached by the store.
        store.pagedTextProvider = { id ->
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    val bytes = db.pdfStore.readPdf(id)
                    if (bytes.isEmpty()) null
                    else com.learnanywhere.data.PdfText.extractPaged(bytes)
                } catch (t: Throwable) {
                    android.util.Log.w("LearnAnywhereApp", "paged text failed for $id", t)
                    null
                }
            }
        }
        store.onRemoved = { id ->
            scope.launch(Dispatchers.IO) {
                db.db.documents().byId(id)?.let { db.db.documents().delete(it) }
                db.pdfStore.deleteAll(id)
            }
        }

        // Hydrate from Room (initial value + future changes).
        scope.launch {
            db.db.documents().observe().collect { rows ->
                val rehydrated = rows.map { row ->
                    val figs = db.pdfStore.figuresFor(row.id).mapIndexed { i, bytes ->
                        com.learnanywhere.data.Figure(
                            id = "${row.id}_$i",
                            title = "Page ${i + 1}",
                            caption = null,
                            mimeType = "image/png",
                            bytes = bytes,
                            source = com.learnanywhere.data.Figure.SourceRef.PDF_PAGE,
                            isFigureCandidate = false
                        )
                    }
                    db.toDocument(row, figs)
                }
                store.hydrate(rehydrated)
                ui.refreshFromStore()
                backfillPdfText(rows)
                rehydrated.forEach { warmSkipMap(it) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Skip-map warming (DESIGN §7.2)
    // ------------------------------------------------------------------

    /** Documents whose skip-map this process has already asked for. */
    private val skipWarmed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Classify a document's boilerplate ahead of time — at startup and on
     * every add — so the sidecar is hot before anyone presses play. Playback
     * and read-with-me read the CACHE only ([DocumentStore.cachedSkipRanges]),
     * which is what keeps the first tap off the network; this is the other
     * half of that bargain.
     *
     * Once per doc per process: [DocumentStore.skipRangesFor] memoizes, but
     * the Room flow re-emits on every write and there is no point re-entering
     * it. Text-less docs are left alone — [backfillPdfText] may still be
     * filling them in, and the next emission warms them for real.
     */
    private fun warmSkipMap(doc: Document) {
        if (doc.text.isBlank()) return
        if (!skipWarmed.add(doc.id)) return
        scope.launch(Dispatchers.IO) {
            try {
                store.skipRangesFor(doc.id)
            } catch (t: Throwable) {
                // Boilerplate is a nicety: warming failures are invisible.
                android.util.Log.w("LearnAnywhereApp", "skip-map warm failed for ${doc.id}", t)
            }
        }
    }

    /**
     * PDFs added before offline text extraction existed have text == "" in
     * Room (and so are silent in audiobook mode). Extract once from the
     * persisted bytes and upsert; the Room flow re-emits with the text
     * attached. Scanned/image-only PDFs stay blank and are retried next cold
     * start (cheap no-op).
     */
    private val backfillInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun backfillPdfText(rows: List<DocumentRow>) {
        rows.filter { it.source == "PDF" && it.text.isBlank() && backfillInFlight.add(it.id) }
            .forEach { row ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val text = com.learnanywhere.data.PdfText.extract(db.pdfStore.readPdf(row.id))
                        if (text.isNotBlank()) db.db.documents().upsert(row.copy(text = text))
                    } finally {
                        backfillInFlight.remove(row.id)
                    }
                }
            }
    }

    // ------------------------------------------------------------------
    // Persistence (called from DocumentStore's add/remove callbacks)
    // ------------------------------------------------------------------

    private fun persistPdf(doc: Document) {
        scope.launch(Dispatchers.IO) {
            // Save the PDF bytes under the doc id.
            val bytes = readBytesFromLocator(doc.pdfLocator)
            if (bytes.isNotEmpty()) db.pdfStore.writePdf(doc.id, bytes)
            // Save figures.
            doc.figures.forEachIndexed { i, f ->
                db.pdfStore.writeFigure(doc.id, i, f.bytes)
            }
            // Save the metadata row.
            db.db.documents().upsert(toRow(doc))
        }
    }

    private fun persistRow(doc: Document) {
        scope.launch(Dispatchers.IO) { db.db.documents().upsert(toRow(doc)) }
    }

    private fun toRow(d: Document): DocumentRow = DocumentRow(
        id = d.id,
        title = d.title,
        source = d.source.name,
        provenance = d.provenance,
        text = d.text,
        order = d.order
    )

    private fun readBytesFromLocator(locator: String?): ByteArray {
        if (locator.isNullOrBlank()) return ByteArray(0)
        return try {
            val uri = Uri.parse(locator)
            when (uri.scheme) {
                null, "file" -> java.io.File(uri.path!!).readBytes()
                "content"  -> contentResolver.openInputStream(uri)!!.readBytes()
                else        -> ByteArray(0)
            }
        } catch (t: Throwable) { ByteArray(0) }
    }

    companion object {
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_TAVILY_API_KEY = "tavily_api_key"
        const val KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_TTS_RATE = "tts_rate_f"
        const val KEY_GROUNDING = "use_grounding"
        const val KEY_WEB_SEARCH = "web_search"
        const val KEY_SPEAK_REPLIES = "speak_replies"
        const val KEY_BARGE_IN = "barge_in"
        const val KEY_NEURAL_TTS = "neural_tts"
        const val DEFAULT_MODEL = com.learnanywhere.agent.Gemini.DEFAULT_MODEL

        @Volatile
        var instance: LearnAnywhereApp? = null
            private set

        fun get(): LearnAnywhereApp =
            instance ?: throw IllegalStateException("LearnAnywhereApp not initialised")
    }
}
