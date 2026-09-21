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
            tools = com.learnanywhere.agent.AgentTools(store)
        )
        ui = com.learnanywhere.ui.UiController(this)

        // ---- persistence plumbing (Document ↔ Room + [AppPdfStore]) ----
        store.onPdfAdded = { doc -> persistPdf(doc) }
        store.onTextOrUrlAdded = { doc -> persistRow(doc) }
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
        const val KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_TTS_RATE = "tts_rate_f"
        const val KEY_GROUNDING = "use_grounding"
        const val KEY_WEB_SEARCH = "web_search"
        const val DEFAULT_MODEL = com.learnanywhere.agent.Gemini.DEFAULT_MODEL

        @Volatile
        var instance: LearnAnywhereApp? = null
            private set

        fun get(): LearnAnywhereApp =
            instance ?: throw IllegalStateException("LearnAnywhereApp not initialised")
    }
}
