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

    // ---- conversation sessions ----
    data class ChatTurn(
        val role: String,                 // "user" | "model"
        val text: String,
        val citedDocTitle: String? = null,
        val citedFigure: String? = null,
        val sources: List<String> = emptyList()
    )

    /** The running transcript of the current conversation (rendered as a thread). */
    val thread = mutableStateOf<List<ChatTurn>>(emptyList())
    /** All saved sessions, newest first (Room flow). */
    val sessions = mutableStateOf<List<com.learnanywhere.app.db.ConversationRow>>(emptyList())

    private var conversationId: String? = null
    private var conversationTitle: String? = null
    private var conversationCreatedAt: Long = 0L
    val error = mutableStateOf<String?>(null)
    /** Non-error status line (e.g. "Connected ✔") shown in Settings. */
    val info = mutableStateOf<String?>(null)

    // ---- player ----
    val player = AudiobookPlayer(app.applicationContext) { app.store.docs }
    val isPlaying = mutableStateOf(false)
    val rate = mutableStateOf(app.prefs.getFloat(LearnAnywhereApp.KEY_TTS_RATE, 1.0f))
    /** Full playback state mirrored for the now-playing surface (presentation only). */
    val playback = mutableStateOf(com.learnanywhere.audio.PlaybackState())

    // ---- settings ----
    val useGrounding = mutableStateOf(app.prefs.getBoolean(LearnAnywhereApp.KEY_GROUNDING, true))
    val webSearch = mutableStateOf(app.prefs.getBoolean(LearnAnywhereApp.KEY_WEB_SEARCH, true))
    /** Voice loop: speak agent replies aloud (default on — this is a voice app). */
    val speakReplies = mutableStateOf(app.prefs.getBoolean(LearnAnywhereApp.KEY_SPEAK_REPLIES, true))
    /** Voice loop v2: interrupt playback by just speaking (Silero VAD watches the mic). */
    val bargeIn = mutableStateOf(app.prefs.getBoolean(LearnAnywhereApp.KEY_BARGE_IN, true))
    /** Piper neural voice instead of the system TTS (falls back automatically on failure). */
    val neuralVoice = mutableStateOf(app.prefs.getBoolean(LearnAnywhereApp.KEY_NEURAL_TTS, true))
    /** Live text of the reply currently streaming in (null when idle). */
    val streamingAnswer = mutableStateOf<String?>(null)

    private val barge = com.learnanywhere.speech.BargeInGuard(app.applicationContext)

    // ---- read-with-me mode (DESIGN roadmap #8) ----
    data class ReadingState(
        val docId: String,
        val docTitle: String,
        val sections: List<String>,
        val index: Int
    )

    /** Non-null while a guided reading session is active. */
    val reading = mutableStateOf<ReadingState?>(null)
    /** True while the current section is being read (vs. answering a question). */
    private var sectionSpeaking = false
    /** Resume reading automatically once a question's answer finishes. */
    private var resumeAfterAnswer = false
    private var wasPlaying = false
    val apiKey = mutableStateOf(app.prefs.getString(LearnAnywhereApp.KEY_GEMINI_API_KEY, "").orEmpty())
    val tavilyKey = mutableStateOf(app.prefs.getString(LearnAnywhereApp.KEY_TAVILY_API_KEY, "").orEmpty())
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
        player.attachNeural(com.learnanywhere.audio.NeuralTts(app.applicationContext))
        player.neuralEnabled = { neuralVoice.value }
        scope.launch {
            player.stateFlow.collect { s ->
                isPlaying.value = s.isPlaying
                playback.value = s
                if (s.error != null) error.value = s.error
                updateBargeGuard()
                if (wasPlaying && !s.isPlaying) onSpeechFinished()
                wasPlaying = s.isPlaying
            }
        }
        scope.launch { app.db.db.conversations().observe().collect { sessions.value = it } }
        scope.launch { voice.state.collect { voiceState.value = it; updateBargeGuard() } }
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
            // Opening the mic silences any playing TTS so the recognizer
            // doesn't transcribe our own voice output.
            barge.stop()
            sectionSpeaking = false   // an interrupted section must not auto-advance
            player.pause()
            voice.start { text -> handleUtterance(text) }
        } else {
            voice.stop()
        }
    }

    /**
     * Route a finished utterance: during a reading session a couple of
     * commands are handled locally (no API round-trip); everything else
     * goes to the agent.
     */
    private fun handleUtterance(text: String) {
        val r = reading.value
        if (r != null) {
            val t = text.trim().lowercase().trimEnd('.', '!', '?')
            when {
                Regex("^(continue|resume|keep (going|reading)|go on)( reading)?$").matches(t) -> {
                    speakSection(r.index); return
                }
                Regex("^next( section)?( please)?$").matches(t) -> {
                    speakSection(r.index + 1); return
                }
                Regex("^((please )?stop( reading)?|end (reading|session)|that's enough)$").matches(t) -> {
                    endReadWithMe(); return
                }
            }
        }
        ask(text)
    }

    /**
     * Voice loop v2: while TTS is speaking and the mic is idle, Silero VAD
     * watches for the user's voice; speech pauses playback and opens the
     * recognizer — interrupt the app by just talking.
     */
    private fun updateBargeGuard() {
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            app.applicationContext, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val shouldRun = playback.value.isPlaying && bargeIn.value && micGranted &&
                voiceState.value == com.learnanywhere.speech.VoiceInput.State.IDLE
        if (shouldRun) barge.start { toggleVoice() } else barge.stop()
    }

    fun toggleBargeIn(v: Boolean) {
        bargeIn.value = v
        app.prefs.edit().putBoolean(LearnAnywhereApp.KEY_BARGE_IN, v).apply()
        updateBargeGuard()
    }

    fun toggleNeuralVoice(v: Boolean) {
        neuralVoice.value = v
        app.prefs.edit().putBoolean(LearnAnywhereApp.KEY_NEURAL_TTS, v).apply()
        player.pause()   // takes effect on the next utterance
    }

    // ------------------------------------------------------------------
    // Read-with-me

    fun startReadWithMe(docId: String) {
        val d = app.store.byId(docId) ?: return
        if (d.text.isBlank()) {
            error.value = "No readable text in this document (scanned PDF?)."
            return
        }
        val secs = com.learnanywhere.core.Sections.split(d.text)
        if (secs.isEmpty()) { error.value = "Nothing to read."; return }
        player.stop()   // clear any audiobook queue
        reading.value = ReadingState(docId, d.title, secs, 0)
        resumeAfterAnswer = false
        speakSection(0)
    }

    fun nextSection() { reading.value?.let { speakSection(it.index + 1) } }
    fun prevSection() { reading.value?.let { speakSection(maxOf(0, it.index - 1)) } }

    fun endReadWithMe(announce: Boolean = false) {
        val wasActive = reading.value != null
        reading.value = null
        sectionSpeaking = false
        resumeAfterAnswer = false
        if (wasActive) {
            if (announce) player.sayOnce("That's the end of the document.")
            else player.pause()
        }
    }

    private fun speakSection(i: Int) {
        val r = reading.value ?: return
        if (i >= r.sections.size) { endReadWithMe(announce = true); return }
        reading.value = r.copy(index = i)
        sectionSpeaking = true
        player.sayOnce("Section ${i + 1}. " + r.sections[i])
    }

    /**
     * Fires on the playing→stopped transition. During a reading session:
     * a section that finished naturally auto-advances; an answer that
     * finished resumes the interrupted section. Debounced + re-checked,
     * because QUEUE_ADD chains can blip the playing state between
     * utterances.
     */
    private fun onSpeechFinished() {
        val r = reading.value ?: return
        val advancing = sectionSpeaking
        val resuming = !sectionSpeaking && resumeAfterAnswer && !busy.value
        if (!advancing && !resuming) return
        scope.launch {
            kotlinx.coroutines.delay(600)
            val rr = reading.value ?: return@launch
            if (player.stateFlow.value.isPlaying || busy.value ||
                voiceState.value != com.learnanywhere.speech.VoiceInput.State.IDLE) return@launch
            if (advancing && sectionSpeaking) {
                sectionSpeaking = false
                speakSection(rr.index + 1)
            } else if (resuming && resumeAfterAnswer) {
                resumeAfterAnswer = false
                speakSection(rr.index)
            }
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

    /** In-flight ask; a new utterance (barge-in) cancels and replaces it. */
    private var askJob: kotlinx.coroutines.Job? = null
    private var askGen = 0

    /** Hard wall-clock ceiling for one turn (model rounds + tools + retries). */
    private val TURN_TIMEOUT_MS = 180_000L

    fun ask(q: String) {
        if (q.isBlank()) return
        val gen = ++askGen
        askJob?.cancel()
        askJob = scope.launch {
            busy.value = true
            error.value = null
            question.value = q
            thread.value = thread.value + ChatTurn("user", q)
            val speak = speakReplies.value
            try {
                kotlinx.coroutines.withTimeout(TURN_TIMEOUT_MS) {
                // Voice loop v2: stream the reply — extract the answer field
                // from the structured JSON as it arrives, chunk into
                // sentences, and start speaking before the model finishes.
                // The extractor is a ONE-SHOT machine, so each tool-loop
                // round gets a fresh one (onRoundStart) — reusing it either
                // swallowed the final answer or spoke raw JSON.
                var extractor = com.learnanywhere.core.StreamingAnswerExtractor()
                var chunker = com.learnanywhere.core.SentenceChunker()
                var spoke = false
                var cueSpoken = false
                val answerAcc = StringBuilder()   // current round's spoken text
                val onDelta: ((String) -> Unit)? = if (speak) { d ->
                    val t = extractor.feed(d)
                    if (t.isNotEmpty()) {
                        answerAcc.append(t)
                        streamingAnswer.value = (streamingAnswer.value ?: "") + t
                        chunker.feed(t).forEach { s ->
                            player.enqueueSay(s, flush = !spoke); spoke = true
                        }
                    }
                } else null
                val onRoundStart = {
                    // Speak any tail of the round that just ended (model
                    // narration like "Let me look that up."), then reset.
                    chunker.flush()?.let {
                        if (speak) { player.enqueueSay(it, flush = !spoke); spoke = true }
                    }
                    extractor = com.learnanywhere.core.StreamingAnswerExtractor()
                    chunker = com.learnanywhere.core.SentenceChunker()
                    answerAcc.setLength(0)
                    streamingAnswer.value = null
                }
                val onToolCall: (String) -> Unit = { name ->
                    val cue = when (name) {
                        "search_papers" -> "Searching for papers…"
                        "search_web" -> "Searching the web…"
                        "download_document" -> "Downloading the document…"
                        "list_library" -> "Checking your library…"
                        else -> "Working…"
                    }
                    streamingAnswer.value = cue
                    // One spoken cue per turn so tool rounds aren't dead air.
                    if (speak && !spoke && !cueSpoken) {
                        cueSpoken = true
                        player.enqueueSay(cue, flush = false)
                    }
                }

                // Read-with-me: give the agent the reading cursor as context,
                // and arrange to resume reading once the answer is spoken.
                val readingExtra = reading.value?.let { r ->
                    resumeAfterAnswer = true
                    "Reading session: you are reading the document \"${r.docTitle}\" to the user, " +
                            "currently at section ${r.index + 1} of ${r.sections.size}. That section: " +
                            "\"${r.sections[r.index].take(1200)}\". " +
                            "Answer in the context of this passage when it applies."
                }
                val extras = listOfNotNull(
                    if (useGrounding.value) null
                    else "Ignore attached documents; answer from general knowledge and tag (general).",
                    readingExtra
                ).joinToString("\n\n").ifBlank { null }
                val reply = app.agent.ask(q, systemExtra = extras, onAnswerDelta = onDelta,
                    onRoundStart = onRoundStart, onToolCall = onToolCall)
                chunker.flush()?.let { if (speak) { player.enqueueSay(it, flush = !spoke); spoke = true } }
                // Stream dropped mid-answer and the non-streamed retry
                // finished it: speak the part that never arrived as deltas.
                if (speak && spoke && answerAcc.isNotEmpty() &&
                    reply.text.length > answerAcc.length &&
                    reply.text.startsWith(answerAcc.toString())) {
                    player.enqueueSay(reply.text.substring(answerAcc.length).trim(), flush = false)
                }
                this@UiController.reply.value = reply
                val citedTitle = reply.citedDocId?.let { id ->
                    app.store.byId(id)?.title
                }
                thread.value = thread.value + ChatTurn(
                    "model", reply.text, citedTitle, reply.citedFigure, reply.sources)
                persistTurn(q, reply.text, citedTitle, reply.citedFigure, reply.sources)
                // Fallback: nothing streamed (schema fallback, tool-only
                // rounds, or stream failure) — speak the final text whole.
                if (speak && !spoke && reply.text.isNotBlank()) {
                    player.sayOnce(reply.text)
                }
                }
            } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                error.value = "Turn timed out after ${TURN_TIMEOUT_MS / 1000}s"
                if (speak) player.sayOnce("Sorry, that took too long. Please try again.")
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c   // superseded by a newer utterance — say nothing
            } catch (t: Throwable) {
                // Errors are surfaced on the error line, NOT written into the
                // conversation as a model turn, and spoken as one short human
                // sentence — never a raw HTTP dump (audit finding).
                error.value = t.message ?: t.toString()
                if (speak) {
                    val phrase = when {
                        t.message?.contains("daily free-tier quota") == true ->
                            "The free A I quota is used up for today."
                        else -> "Sorry, I couldn't reach the model."
                    }
                    player.sayOnce(phrase)
                }
            } finally {
                // Only the CURRENT turn may clear shared state — a cancelled
                // predecessor running its finally must not blank the UI of
                // the ask that replaced it.
                if (gen == askGen) {
                    busy.value = false
                    streamingAnswer.value = null
                }
            }
        }
    }

    /** Write one exchange to the current session (creating it on first ask). */
    private fun persistTurn(
        q: String, answer: String,
        citedDocTitle: String?, citedFigure: String?, sources: List<String>
    ) = scope.launch(Dispatchers.IO) {
        try {
            val dao = app.db.db.conversations()
            val now = System.currentTimeMillis()
            val cid = conversationId ?: java.util.UUID.randomUUID().toString().also {
                conversationId = it
                conversationTitle = q.take(60)
                conversationCreatedAt = now
            }
            dao.upsert(com.learnanywhere.app.db.ConversationRow(
                id = cid,
                title = conversationTitle ?: q.take(60),
                createdAt = conversationCreatedAt,
                updatedAt = now))
            dao.insert(com.learnanywhere.app.db.MessageRow(
                id = java.util.UUID.randomUUID().toString(),
                conversationId = cid, role = "user", text = q,
                citedDocTitle = null, citedFigure = null, sources = null,
                createdAt = now))
            dao.insert(com.learnanywhere.app.db.MessageRow(
                id = java.util.UUID.randomUUID().toString(),
                conversationId = cid, role = "model", text = answer,
                citedDocTitle = citedDocTitle, citedFigure = citedFigure,
                sources = sources.joinToString("\n").ifBlank { null },
                createdAt = now + 1))
        } catch (t: Throwable) {
            // Persistence must never break the ask flow.
            android.util.Log.w("UiController", "persistTurn failed", t)
        }
    }

    /** Reopen a saved session: transcript into the thread, turns into the agent. */
    fun resumeSession(row: com.learnanywhere.app.db.ConversationRow) = scope.launch(Dispatchers.IO) {
        try {
            val msgs = app.db.db.conversations().messages(row.id)
            conversationId = row.id
            conversationTitle = row.title
            conversationCreatedAt = row.createdAt
            app.agent.restoreHistory(msgs.map { it.role to it.text })
            thread.value = msgs.map { m ->
                ChatTurn(m.role, m.text, m.citedDocTitle, m.citedFigure,
                    m.sources?.split("\n")?.filter { it.isNotBlank() } ?: emptyList())
            }
            reply.value = null
            error.value = null
        } catch (t: Throwable) {
            error.value = "Couldn't open session: ${t.message}"
        }
    }

    fun deleteSession(row: com.learnanywhere.app.db.ConversationRow) = scope.launch(Dispatchers.IO) {
        try {
            app.db.db.conversations().deleteMessages(row.id)
            app.db.db.conversations().deleteConversation(row.id)
            if (row.id == conversationId) newChat()
        } catch (t: Throwable) {
            error.value = "Couldn't delete session: ${t.message}"
        }
    }

    fun toggleSpeakReplies(v: Boolean) {
        speakReplies.value = v
        app.prefs.edit().putBoolean(LearnAnywhereApp.KEY_SPEAK_REPLIES, v).apply()
        if (!v) player.pause()
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

    fun saveTavilyKey(k: String) {
        tavilyKey.value = k
        app.prefs.edit().putString(LearnAnywhereApp.KEY_TAVILY_API_KEY, k).apply()
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

    /**
     * Start a fresh conversation. The old one stays saved (sessions list);
     * this only detaches from it.
     */
    fun newChat() {
        app.agent.clearHistory()
        conversationId = null
        conversationTitle = null
        conversationCreatedAt = 0L
        thread.value = emptyList()
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
