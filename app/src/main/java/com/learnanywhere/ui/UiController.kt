package com.learnanywhere.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.learnanywhere.LearnAnywhereApp
import com.learnanywhere.audio.AudiobookPlayer
import com.learnanywhere.data.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Bridges the app's data/agent/audio layers to the Compose UI.
 *
 * Why hand-rolled? This app has exactly one Activity and one screen; wiring it
 * through a ViewModel + StateFlow + DI graph would hide more code than it
 * saves. A single UiController is the smallest surface that's still testable.
 */
class UiController(
    private val app: LearnAnywhereApp,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {

    // ---- documents ----
    val docs = mutableStateOf(app.store.docs)
    val selected = mutableStateOf(app.store.docs.map { it.id }.toSet())

    // ---- agent ----
    val question = mutableStateOf<String?>(null)
    val reply = mutableStateOf<com.learnanywhere.agent.LearnAnywhereAgent.AgentReply?>(null)
    val busy = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    /** Non-error status line (e.g. "Connected ✔") shown in Settings. */
    val info = mutableStateOf<String?>(null)

    // ---- player ----
    val player = AudiobookPlayer(app.applicationContext) { app.store.docs }
    val isPlaying = mutableStateOf(false)
    val rate = mutableStateOf(app.prefs.getFloat(LearnAnywhereApp.KEY_TTS_RATE, 1.0f))

    // ---- settings ----
    val useGrounding = mutableStateOf(app.prefs.getBoolean(LearnAnywhereApp.KEY_GROUNDING, true))
    val webSearch = mutableStateOf(app.prefs.getBoolean(LearnAnywhereApp.KEY_WEB_SEARCH, true))
    val apiKey = mutableStateOf(app.prefs.getString(LearnAnywhereApp.KEY_GEMINI_API_KEY, "").orEmpty())
    val model = mutableStateOf(app.prefs.getString(LearnAnywhereApp.KEY_GEMINI_MODEL, LearnAnywhereApp.DEFAULT_MODEL).orEmpty())

    // ---- voice input (on-device ASR; DESIGN.md §3.1) ----
    val voice = com.learnanywhere.speech.VoiceInput(app.applicationContext)
    val voiceState = mutableStateOf(com.learnanywhere.speech.VoiceInput.State.IDLE)
    val voicePartial = mutableStateOf("")
    // A/B transcripts of the last utterance (zipformer vs whisper tiny.en)
    val lastZipformer = mutableStateOf<String?>(null)
    val whisperText = mutableStateOf<String?>(null)
    val whisperBusy = mutableStateOf(false)

    init {
        scope.launch {
            player.stateFlow.collect { s ->
                isPlaying.value = s.isPlaying
                if (s.error != null) error.value = s.error
            }
        }
        scope.launch { voice.state.collect { voiceState.value = it } }
        scope.launch { voice.partial.collect { voicePartial.value = it } }
        scope.launch { voice.error.collect { if (it != null) error.value = it } }
        scope.launch { voice.lastZipformer.collect { lastZipformer.value = it } }
        scope.launch { voice.whisperText.collect { whisperText.value = it } }
        scope.launch { voice.whisperBusy.collect { whisperBusy.value = it } }
    }

    /**
     * Push-to-talk: tap starts listening (live partials in the Ask field);
     * the utterance auto-asks when the endpointer fires, or tap again to
     * finish early with whatever was recognized.
     */
    fun toggleVoice() {
        if (voiceState.value == com.learnanywhere.speech.VoiceInput.State.IDLE) {
            voice.start { text -> ask(text) }
        } else {
            voice.stop()
        }
    }

    // ------------------------------------------------------------------
    // Document actions

    fun addPdf(uri: Uri) = scope.launch(Dispatchers.IO) {
        // addPdf returns a Result — getOrThrow() so failures reach the catch
        // instead of vanishing inside the Result.
        try {
            app.store.addPdf(uri).getOrThrow()
            refreshFromStore()
        } catch (t: Throwable) {
            error.value = "Couldn't add PDF: ${t.message}"
        }
    }

    fun addText(title: String, body: String) = scope.launch(Dispatchers.IO) {
        app.store.addText(title, body)
        refreshFromStore()
    }

    fun addUrl(url: String, title: String = "") = scope.launch(Dispatchers.IO) {
        try {
            app.store.addUrl(url, title.ifBlank { url }).getOrThrow()
            refreshFromStore()
        } catch (t: Throwable) {
            error.value = "Couldn't fetch URL: ${t.message}"
        }
    }

    fun removeDoc(id: String) = scope.launch {
        app.store.remove(id)
        // Byte + row deletion is wired in LearnAnywhereApp (store.onRemoved).
        // The Room flow observer then refreshFromStore() for us.
    }

    /** Called by LearnAnywhereApp's Room-flow observer after a (re)hydrate. */
    fun refreshFromStore() {
        docs.value = app.store.docs
        val valid = app.store.docs.map { it.id }.toSet()
        selected.value = selected.value.filter { it in valid }.toSet()
    }

    fun toggleSelected(id: String) {
        if (selected.value.contains(id)) selected.value = selected.value - id
        else selected.value = selected.value + id
    }

    // ------------------------------------------------------------------
    // Agent

    fun ask(q: String) {
        if (q.isBlank()) return
        scope.launch {
            busy.value = true
            error.value = null
            question.value = q
            try {
                val reply = if (useGrounding.value) {
                    app.agent.ask(q)
                } else {
                    app.agent.ask(q, systemExtra = "Ignore attached documents; answer from general knowledge and tag (general).")
                }
                this@UiController.reply.value = reply
            } catch (t: Throwable) {
                error.value = t.message ?: t.toString()
            } finally {
                busy.value = false
            }
        }
    }

    // ------------------------------------------------------------------
    // Player

    fun listen() = scope.launch {
        val ids = selected.value.toList()
        if (ids.isEmpty()) { error.value = "Select at least one document to listen to."; return@launch }
        error.value = null
        player.play(ids, rate = rate.value)
    }
    fun pause()    = scope.launch { player.pause() }
    fun resume()   = scope.launch { player.resume() }
    fun next()     = scope.launch { player.next() }
    fun prev()     = scope.launch { player.prev() }
    fun setRate(r: Float) = scope.launch {
        rate.value = r
        player.setRate(r)
    }

    fun stop() = scope.launch { player.stop() }

    // ---- car bridge helpers (used by LearnStudyProvider / MediaBrowserService) ----

    fun listenOrPlayAll() = scope.launch {
        val ids = if (selected.value.isNotEmpty()) selected.value.toList() else docs.value.map { it.id }
        if (ids.isEmpty()) return@launch
        player.play(ids, rate = rate.value)
    }

    fun playSingle(id: String) = scope.launch {
        player.play(listOf(id), rate = rate.value)
    }

    fun playingStatus(): String =
        if (isPlaying.value) "Playing ${docs.value.getOrNull(player.stateFlow.value.cursor)?.title.orEmpty()}"
        else "Stopped"

    fun docsCount(): Int = docs.value.size
    fun docsList(): List<Document> = docs.value

    // ---- (a)+(c) in-car "ask" and "figures" as spoken outputs ----

    /**
     * In-car "Ask the agent": speak the latest agent reply (if any). On the
     * phone, open the full visual agent instead. This is the spoken fallback
     * Android Auto shows for the `learnanywhere://special/ask` media item.
     */
    fun speakLatestReplyOrHint() = scope.launch {
        val last = reply.value
        val text = if (last != null) last.text
        else "No agent reply yet. Open LearnAnywhere on the phone to ask."
        player.sayOnce(text)
    }

    /**
     * In-car "Figures": read a one-line summary of each figure across docs.
     * (Phone users tap the "Caption" button on each figure for full captions.)
     */
    fun speakFiguresSummary() = scope.launch {
        val lines = docs.value.flatMap { d ->
            d.figures.map { f ->
                val cap = captionResult.value[f.id] ?: f.caption
                if (cap.isNullOrBlank()) "${d.title} — ${f.title}"
                else "${d.title} — ${f.title}: $cap"
            }
        }
        player.sayOnce(if (lines.isEmpty()) "No figures in your library yet."
                       else "Figures. " + lines.joinToString(separator = "  ") { it })
    }

    // ------------------------------------------------------------------
    // Settings

    fun saveApiKey(k: String) {
        apiKey.value = k
        app.prefs.edit().putString(LearnAnywhereApp.KEY_GEMINI_API_KEY, k).apply()
    }

    fun saveModel(m: String) {
        model.value = m
        app.prefs.edit().putString(LearnAnywhereApp.KEY_GEMINI_MODEL, m).apply()
    }

    fun testConnection() = scope.launch(Dispatchers.IO) {
        error.value = null
        info.value = null
        try {
            val key = apiKey.value
            val m = model.value.ifBlank { LearnAnywhereApp.DEFAULT_MODEL }
            if (key.isBlank()) { error.value = "Paste a free API key first (aistudio.google.com)."; return@launch }
            info.value = "Testing…"
            com.learnanywhere.agent.Gemini({ key }, { m }).ping()
            info.value = "Connected ✔ ($m)"
        } catch (t: Throwable) {
            info.value = null
            error.value = t.message ?: t.toString()
        }
    }

    fun toggleGrounding(v: Boolean) {
        useGrounding.value = v
        app.prefs.edit().putBoolean(LearnAnywhereApp.KEY_GROUNDING, v).apply()
    }

    fun toggleWebSearch(v: Boolean) {
        webSearch.value = v
        app.prefs.edit().putBoolean(LearnAnywhereApp.KEY_WEB_SEARCH, v).apply()
    }

    /** Forget the multi-turn conversation and clear the reply card. */
    fun newChat() {
        app.agent.clearHistory()
        reply.value = null
        question.value = null
        error.value = null
    }

    // ---- (a) on-demand figure caption (Gemini vision) ----

    val captionBusy = mutableStateOf(false)
    val captionResult = mutableStateOf<Map<String, String>>(emptyMap())  // figureId -> caption

    fun captionFigure(docId: String, fig: com.learnanywhere.data.Figure) {
        if (captionBusy.value) return
        scope.launch {
            captionBusy.value = true
            try {
                val d = app.store.byId(docId) ?: return@launch
                val text = app.agent.captionFigure(d, fig)
                captionResult.value = captionResult.value + (fig.id to text)
            } finally {
                captionBusy.value = false
            }
        }
    }

    // -----------------------------------------------------------------

}
