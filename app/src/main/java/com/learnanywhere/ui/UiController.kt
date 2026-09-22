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
        val sources: List<String> = emptyList(),
        /** 1-based page of the cited document the figure/table is on. */
        val citedPage: Int? = null
    )

    /** The running transcript of the current conversation (rendered as a thread). */
    val thread = mutableStateOf<List<ChatTurn>>(emptyList())
    /** All saved sessions, newest first (Room flow). */
    val sessions = mutableStateOf<List<com.learnanywhere.app.db.ConversationRow>>(emptyList())

    /**
     * Row id of the session the live [thread] belongs to, or null before the
     * first turn of a new conversation is persisted. The pager needs this to
     * tell "render the live thread" from "render the stored transcript".
     */
    val currentSessionId = mutableStateOf<String?>(null)

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
    /**
     * Utterance ids spoken for the current answer. The reply bubble highlights
     * `playback.activeText` only while the speaking utterance is one of these —
     * otherwise an unrelated utterance (a cue, a read-with-me section) whose
     * text happens to occur in the bubble would light it up.
     */
    val replyUtteranceIds = mutableStateOf<Set<String>>(emptySet())

    private val barge = com.learnanywhere.speech.BargeInGuard(app.applicationContext)

    // ---- read-with-me mode (DESIGN roadmap #8) ----
    data class ReadingState(
        val docId: String,
        val docTitle: String,
        /**
         * Sections carry their offset in the stored text (DESIGN §7.4) so a
         * voice seek's quote offset and the skip-map share one geometry.
         */
        val sections: List<com.learnanywhere.core.Sections.Section>,
        val index: Int
    )

    /** Non-null while a guided reading session is active. */
    val reading = mutableStateOf<ReadingState?>(null)
    /** Utterance ids of the chain speaking the current section (read-along highlight). */
    val readingUtteranceIds = mutableStateOf<Set<String>>(emptySet())
    /** 1-based PDF page the current section starts on; null while unknown or non-PDF. */
    val readingPage = mutableStateOf<Int?>(null)
    /** Page per section index; O(document) to compute, so never recomputed. */
    private val readingPages = HashMap<Int, Int?>()
    /** True while the current section is being read (vs. answering a question). */
    private var sectionSpeaking = false
    /** Resume reading automatically once a question's answer finishes. */
    private var resumeAfterAnswer = false
    private var wasPlaying = false

    /**
     * Voice seek (DESIGN §7.3): where the agent's `seek_quote` was located,
     * held until the reply has finished being spoken. Jumping mid-sentence
     * would cut the confirmation ("Jumping to the related-work section.") off
     * in the middle of itself.
     */
    private data class PendingSeek(val docId: String, val offset: Int)
    private var pendingSeek: PendingSeek? = null
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
            pendingSeek = null        // ...nor may an armed jump fire on the pause
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

    /**
     * [startIndex] exists for voice seek (DESIGN §7.3): "drop me in the part
     * about X" from idle starts the session already parked on that section.
     *
     * Sectioning is skip-map filtered from the CACHE only (DESIGN §7.2): the
     * first tap must never block on a classification round-trip, and warming
     * at add-time/startup is what makes the sidecar hot by now. A cold cache
     * simply reads the boilerplate too, which is the old behaviour.
     */
    fun startReadWithMe(docId: String, startIndex: Int = 0) {
        val d = app.store.byId(docId) ?: return
        if (d.text.isBlank()) {
            error.value = "No readable text in this document (scanned PDF?)."
            return
        }
        val secs = com.learnanywhere.core.Sections.splitWithOffsets(
            d.text, skip = app.store.cachedSkipRanges(docId))
        if (secs.isEmpty()) { error.value = "Nothing to read."; return }
        player.stop()   // clear any audiobook queue
        readingPages.clear()
        readingPage.value = null
        reading.value = ReadingState(docId, d.title, secs, 0)
        resumeAfterAnswer = false
        pendingSeek = null
        speakSection(startIndex.coerceIn(0, secs.size - 1))
    }

    fun nextSection() { reading.value?.let { speakSection(it.index + 1) } }
    fun prevSection() { reading.value?.let { speakSection(maxOf(0, it.index - 1)) } }

    fun endReadWithMe(announce: Boolean = false) {
        val wasActive = reading.value != null
        reading.value = null
        sectionSpeaking = false
        resumeAfterAnswer = false
        pendingSeek = null
        readingUtteranceIds.value = emptySet()
        readingPage.value = null
        readingPages.clear()
        if (wasActive) {
            if (announce) player.sayOnce("That's the end of the document.")
            else player.pause()
        }
    }

    /**
     * Speak one section as a sentence chain: each sentence is its own
     * utterance, so the card can highlight the sentence being spoken and a
     * rate change lands within a sentence (DESIGN §6.4/§6.6).
     *
     * Auto-advance hangs off [AudiobookPlayer.sayChain]'s onDone, which fires
     * only on natural completion — any interrupt (barge-in, a question, stop)
     * silently cancels it, which is exactly the wanted behaviour. The Next
     * button stays the manual path.
     */
    private fun speakSection(i: Int) {
        val r = reading.value ?: return
        if (i >= r.sections.size) { endReadWithMe(announce = true); return }
        reading.value = r.copy(index = i)
        sectionSpeaking = true
        updateReadingPage(r.docId, r.sections, i)
        val ids = player.sayChain(
            listOf("Section ${i + 1}.") + com.learnanywhere.core.Sentences.split(r.sections[i].text),
            onDone = {
                // Re-check: onDone is cancelled on supersede, but the session
                // may still have moved on between the last sentence and here.
                val rr = reading.value
                if (rr != null && rr.docId == r.docId && rr.index == i) speakSection(i + 1)
            })
        readingUtteranceIds.value = ids.toSet()
    }

    /** Resolve (once per section) which PDF page the section starts on. */
    private fun updateReadingPage(
        docId: String, sections: List<com.learnanywhere.core.Sections.Section>, i: Int
    ) {
        if (readingPages.containsKey(i)) { readingPage.value = readingPages[i]; return }
        readingPage.value = null
        scope.launch {
            val p = kotlinx.coroutines.withContext(Dispatchers.IO) {
                app.store.pagedTextFor(docId)?.let {
                    com.learnanywhere.core.PageMap.pageFor(it, sections[i].text)
                }
            }
            readingPages[i] = p
            val rr = reading.value
            if (rr != null && rr.docId == docId && rr.index == i) readingPage.value = p
        }
    }

    /**
     * Fires on the playing→stopped transition. Section auto-advance lives in
     * the chain's onDone; what remains here is resuming an interrupted
     * section once a question's answer has been spoken (answers stream through
     * enqueueSay, which has no completion callback). Debounced + re-checked,
     * because QUEUE_ADD chains can blip the playing state between utterances.
     */
    private fun onSpeechFinished() {
        // A pending voice seek outranks resuming: the user asked to MOVE, so
        // the old cursor is not where they want to be. (Arming already
        // cleared resumeAfterAnswer; this is the ordering, made explicit.)
        if (pendingSeek != null) { scheduleSeek(); return }
        reading.value ?: return
        if (sectionSpeaking || !resumeAfterAnswer || busy.value) return
        scope.launch {
            kotlinx.coroutines.delay(600)
            val rr = reading.value ?: return@launch
            if (player.stateFlow.value.isPlaying || busy.value ||
                voiceState.value != com.learnanywhere.speech.VoiceInput.State.IDLE) return@launch
            if (resumeAfterAnswer) {
                resumeAfterAnswer = false
                speakSection(rr.index)
            }
        }
    }

    // ------------------------------------------------------------------
    // Voice seek (DESIGN §7.3)

    /**
     * Arm the jump the agent's `seek_quote` describes, or do nothing at all.
     *
     * Every step is allowed to miss — no quote, no resolvable document, a
     * quote that drifted too far to locate — and a miss is silent by design:
     * the spoken answer already stands on its own (DESIGN §7.3, "misses
     * degrade gracefully").
     *
     * [spoken] is whether this turn actually queued speech. If it did, the
     * jump waits for that speech to finish naturally; if it didn't (replies
     * muted), there is nothing to wait for and it happens now.
     */
    private fun armSeek(
        reply: com.learnanywhere.agent.LearnAnywhereAgent.AgentReply, spoken: Boolean
    ) {
        val quote = reply.seekQuote?.takeIf { it.isNotBlank() } ?: return
        // The reply's own cited-document resolution first; with no citation,
        // a single attached document is unambiguous enough to act on.
        val docId = reply.citedDocId ?: selected.value.singleOrNull() ?: return
        val doc = app.store.byId(docId) ?: return
        val offset = com.learnanywhere.core.TextLocate.find(doc.text, quote) ?: return
        // We are moving, not resuming: the old section must not come back.
        resumeAfterAnswer = false
        pendingSeek = PendingSeek(docId, offset)
        if (spoken) scheduleSeek() else performSeek()
    }

    /**
     * Fire the pending seek once the voice is actually quiet. Debounced and
     * re-checked exactly like the resume path, because a QUEUE_ADD chain blips
     * playing→stopped between utterances; and re-entrant-safe, because both
     * the arming call and the playing→stopped handler may schedule it.
     *
     * Arming schedules a check too, not just the transition handler: a short
     * answer can finish speaking before the turn returns, and then no
     * playing→stopped edge is ever left to wait for.
     */
    private fun scheduleSeek() {
        scope.launch {
            kotlinx.coroutines.delay(600)
            if (player.stateFlow.value.isPlaying || busy.value ||
                voiceState.value != com.learnanywhere.speech.VoiceInput.State.IDLE) return@launch
            performSeek()
        }
    }

    /**
     * Do the jump: re-section the target document (same geometry as
     * read-with-me — cached skip-map, section offsets) and speak from the
     * section the offset lands in. Reading the same document moves the
     * cursor; anything else (idle, or reading a different document) starts a
     * fresh session there, without the end-of-document announcement.
     */
    private fun performSeek() {
        val seek = pendingSeek ?: return
        pendingSeek = null
        val d = app.store.byId(seek.docId) ?: return
        val secs = com.learnanywhere.core.Sections.splitWithOffsets(
            d.text, skip = app.store.cachedSkipRanges(seek.docId))
        if (secs.isEmpty()) return
        val idx = com.learnanywhere.core.Seek.sectionIndexFor(secs, seek.offset)
        resumeAfterAnswer = false
        val r = reading.value
        if (r != null && r.docId == seek.docId) {
            // Re-sectioning may differ from the session's own split if the
            // skip-map warmed in between, so the sections go along with the
            // index — the offset we resolved belongs to THIS geometry.
            readingPages.clear()
            readingPage.value = null
            reading.value = r.copy(sections = secs)
            speakSection(idx)
        } else {
            if (r != null) endReadWithMe(announce = false)
            startReadWithMe(seek.docId, startIndex = idx)
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
        pendingSeek = null   // a new question supersedes the last one's jump
        askJob?.cancel()
        askJob = scope.launch {
            busy.value = true
            error.value = null
            question.value = q
            thread.value = thread.value + ChatTurn("user", q)
            replyUtteranceIds.value = emptySet()   // last turn's highlight must not linger
            val speak = speakReplies.value
            // Every utterance carrying answer text, for the karaoke highlight.
            val spokenIds = LinkedHashSet<String>()
            val say = { text: String, flush: Boolean ->
                spokenIds.add(player.enqueueSay(text, flush))
                replyUtteranceIds.value = spokenIds.toSet()
            }
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
                            say(s, !spoke); spoke = true
                        }
                    }
                } else null
                val onRoundStart = {
                    // Speak any tail of the round that just ended (model
                    // narration like "Let me look that up."), then reset.
                    chunker.flush()?.let {
                        if (speak) { say(it, !spoke); spoke = true }
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
                    // The answer supersedes the section chain either way; clear
                    // the flag so a TYPED question resumes like a spoken one
                    // (toggleVoice clears it on the spoken path).
                    sectionSpeaking = false
                    resumeAfterAnswer = true
                    "Reading session: you are reading the document \"${r.docTitle}\" to the user, " +
                            "currently at section ${r.index + 1} of ${r.sections.size}. That section: " +
                            "\"${r.sections[r.index].text.take(1200)}\". " +
                            "Answer in the context of this passage when it applies."
                }
                val extras = listOfNotNull(
                    if (useGrounding.value) null
                    else "Ignore attached documents; answer from general knowledge and tag (general).",
                    readingExtra
                ).joinToString("\n\n").ifBlank { null }
                val reply = app.agent.ask(q, systemExtra = extras, onAnswerDelta = onDelta,
                    onRoundStart = onRoundStart, onToolCall = onToolCall)
                chunker.flush()?.let { if (speak) { say(it, !spoke); spoke = true } }
                // Stream dropped mid-answer and the non-streamed retry
                // finished it: speak the part that never arrived as deltas.
                if (speak && spoke && answerAcc.isNotEmpty() &&
                    reply.text.length > answerAcc.length &&
                    reply.text.startsWith(answerAcc.toString())) {
                    say(reply.text.substring(answerAcc.length).trim(), false)
                }
                this@UiController.reply.value = reply
                val citedTitle = reply.citedDocId?.let { id ->
                    app.store.byId(id)?.title
                }
                // Model-supplied and unvalidated — clamp before anyone indexes figures with it.
                val citedPage = clampCitedPage(
                    reply.citedPage, reply.citedDocId?.let { app.store.byId(it)?.figures?.size } ?: 0)
                thread.value = thread.value + ChatTurn(
                    "model", reply.text, citedTitle, reply.citedFigure, reply.sources, citedPage)
                persistTurn(q, reply.text, citedTitle, reply.citedFigure, citedPage, reply.sources)
                // Fallback: nothing streamed (schema fallback, tool-only
                // rounds, or stream failure) — speak the final text whole.
                // enqueueSay (not sayOnce) so the highlight has an id to follow.
                if (speak && !spoke && reply.text.isNotBlank()) {
                    say(reply.text, true); spoke = true
                }
                // Voice seek: armed only once we know whether anything was
                // actually queued to speak (DESIGN §7.3).
                armSeek(reply, spoken = spoke)
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
        citedDocTitle: String?, citedFigure: String?, citedPage: Int?, sources: List<String>
    ) = scope.launch(Dispatchers.IO) {
        try {
            val dao = app.db.db.conversations()
            val now = System.currentTimeMillis()
            val cid = currentSessionId.value ?: java.util.UUID.randomUUID().toString().also {
                currentSessionId.value = it
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
                citedPage = citedPage,
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
            currentSessionId.value = row.id
            conversationTitle = row.title
            conversationCreatedAt = row.createdAt
            app.agent.restoreHistory(msgs.map { it.role to it.text })
            thread.value = msgs.map { it.toTurn() }
            reply.value = null
            error.value = null
        } catch (t: Throwable) {
            error.value = "Couldn't open session: ${t.message}"
        }
    }

    /**
     * Read one session's transcript for display. Unlike [resumeSession] this
     * mutates nothing — the pager renders neighbouring session pages with it,
     * and only the page the user settles on is actually resumed.
     */
    suspend fun loadTranscript(cid: String): List<ChatTurn> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                app.db.db.conversations().messages(cid).map { it.toTurn() }
            } catch (t: Throwable) {
                android.util.Log.w("UiController", "loadTranscript failed", t)
                emptyList()
            }
        }

    private fun com.learnanywhere.app.db.MessageRow.toTurn() = ChatTurn(
        role, text, citedDocTitle, citedFigure,
        sources?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
        citedPage)

    fun deleteSession(row: com.learnanywhere.app.db.ConversationRow) = scope.launch(Dispatchers.IO) {
        try {
            app.db.db.conversations().deleteMessages(row.id)
            app.db.db.conversations().deleteConversation(row.id)
            if (row.id == currentSessionId.value) newChat()
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
        pendingSeek = null   // the user chose what to play; don't jump afterwards
        player.play(ids, rate = rate.value)
    }
    fun pause()    = scope.launch { pendingSeek = null; player.pause() }
    fun resume()   = scope.launch { player.resume() }
    fun next()     = scope.launch { player.next() }
    fun prev()     = scope.launch { player.prev() }
    /** Persisted: the pref was read at startup but never written (DESIGN §6.6). */
    fun setRate(r: Float) = scope.launch {
        rate.value = r
        app.prefs.edit().putFloat(LearnAnywhereApp.KEY_TTS_RATE, r).apply()
        player.setRate(r)
    }

    fun stop() = scope.launch { pendingSeek = null; player.stop() }

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
        currentSessionId.value = null
        conversationTitle = null
        conversationCreatedAt = 0L
        thread.value = emptyList()
        reply.value = null
        question.value = null
        error.value = null
        replyUtteranceIds.value = emptySet()
    }

    /**
     * Asking from Home opens a fresh session (DESIGN §6.1): detach from the
     * saved session the pager last resumed. A conversation whose first turn
     * hasn't been persisted yet has no row id and is left alone — asking again
     * from Home continues it rather than throwing it away.
     */
    fun detachSession() {
        if (currentSessionId.value != null) newChat()
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
