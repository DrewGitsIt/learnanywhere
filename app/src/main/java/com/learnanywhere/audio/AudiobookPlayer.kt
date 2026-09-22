package com.learnanywhere.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.learnanywhere.data.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * A [TextToSpeech]-backed "audiobook" player. Exposes a small [StateFlow]
 * surface so both the phone UI and the Android Auto MediaTemplate can drive
 * it (play / pause / stop / next / prev / rate).
 *
 * Documents are spoken in full: the text is split into sentence-aligned
 * chunks (each well under TTS's max input length) and enqueued with
 * QUEUE_ADD, so the engine flows from one chunk to the next. When the last
 * chunk of a document finishes we advance to the next document in the queue.
 *
 * TTS engine init is asynchronous; requests that arrive before [onInit]
 * are parked and replayed once the engine is ready, so the first tap works.
 *
 * Every spoken unit is one engine utterance with its own id, and its FULL
 * text is published as [PlaybackState.activeText] when that utterance starts.
 * That pairing is what the read-along highlight tracks (DESIGN §6.4).
 *
 * Threading: [SpeechBookkeeper] and the chunk fields are touched only from
 * [scope] (Main.immediate). Both engines deliver their callbacks on their own
 * threads, so both hop onto the scope first.
 */
class AudiobookPlayer(
    private val context: Context,
    private val library: () -> List<Document>
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val state = MutableStateFlow(PlaybackState())
    val stateFlow: StateFlow<PlaybackState> get() = state.asStateFlow()

    // Chunk bookkeeping for the document currently being spoken.
    private var chunks: List<String> = emptyList()
    private var chunkIds: List<String> = emptyList()
    private var chunkIdx = 0
    /** Utterance id of the LAST chunk of the current document; null in sayOnce mode. */
    private var finalUtteranceOfDoc: String? = null
    /** Speech requests that arrived before the engine was ready, replayed by [onInit]. */
    private val pendingSpeech = ArrayList<() -> Unit>()
    /** id → text of everything in flight, plus the armed chain. See [SpeechBookkeeper]. */
    private val book = SpeechBookkeeper()

    // ---- neural voice (Piper) routing ----
    private var neural: NeuralTts? = null
    var neuralEnabled: () -> Boolean = { false }
    @Volatile private var neuralFailed = false

    /** Attach the Piper engine; its callbacks reuse the same utterance flow. */
    fun attachNeural(n: NeuralTts) {
        neural = n
        n.onStart = { id -> scope.launch { handleUttStart(id) } }
        n.onDone = { id -> scope.launch { handleUttDone(id) } }
        n.onFailed = { id, text, err ->
            scope.launch {
                // Permanent fallback to the system voice for this session.
                neuralFailed = true
                publish { it.copy(error = err) }
                if (text.isNotBlank()) engineSpeak(text, id, flush = false)
            }
        }
    }

    private fun handleUttStart(utteranceId: String?) {
        // An unknown id is a callback from a queue we've already abandoned;
        // publishing it would blank the text the UI is highlighting right now.
        val text = book.textOf(utteranceId) ?: return
        val i = chunkIds.indexOf(utteranceId)
        if (i >= 0) chunkIdx = i
        publish {
            it.copy(
                isPlaying = true, activeUtterance = utteranceId,
                activeText = text, activeTextPreview = truncate(text, PREVIEW_CHARS)
            )
        }
    }

    private fun handleUttDone(utteranceId: String?) {
        if (!book.retire(utteranceId)) return   // stale: superseded before it finished
        val chainEnd = book.claimChainEnd(utteranceId)
        if (chainEnd != null) {
            publishIdle()
            chainEnd.onDone?.invoke()
            return
        }
        // Mid-chain: the following sentences are already queued behind it.
        if (book.isChainMember(utteranceId)) return

        val finalId = finalUtteranceOfDoc
        if (finalId != null) {
            if (utteranceId == finalId) scope.launch { advanceDoc() }
            // else: an intermediate chunk finished; the next queued chunk
            // fires onStart on its own.
        } else if (utteranceId == state.value.activeUtterance) {
            publishIdle()   // a stand-alone sayOnce/enqueueSay finished
        }
    }

    private fun handleUttError(utteranceId: String?) {
        if (!book.retire(utteranceId)) return
        // An error is not natural completion: a chain waiting on it must not fire.
        book.clearChain()
        publish {
            it.copy(
                isPlaying = false, activeUtterance = null, activeText = "",
                activeTextPreview = "", error = "TTS error"
            )
        }
    }

    /**
     * Route one utterance to whichever engine is active. Sole registration
     * point for the id → text map, so no path can speak text the UI can't see.
     */
    private fun engineSpeak(text: String, id: String, flush: Boolean) {
        book.register(id, text)
        val n = neural
        if (n != null && !neuralFailed && neuralEnabled()) {
            n.setRate(state.value.rate)
            n.enqueue(id, text, flush)
        } else {
            try {
                val rc = tts?.speak(text,
                    if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
                if (rc != TextToSpeech.SUCCESS) book.forget(id)
            } catch (t: Throwable) {
                book.forget(id)
                publish { it.copy(error = t.message) }
            }
        }
    }

    // The system engine delivers these on a binder thread; hop to the scope so
    // all bookkeeping stays single-threaded (the Piper path already does).
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { scope.launch { handleUttStart(utteranceId) } }
        override fun onDone(utteranceId: String?) { scope.launch { handleUttDone(utteranceId) } }
        override fun onError(utteranceId: String?) { scope.launch { handleUttError(utteranceId) } }
        override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
    }

    /** Public surface driven by both phone UI and car UI. */
    fun play(docIds: List<String>, rate: Float = 1.0f, locale: String = "en-US") {
        scope.launch {
            initIfNeeded()
            // Starting a document supersedes any say/chain, parked or in flight.
            beginSpeech()
            state.value = state.value.copy(
                queue = docIds, cursor = 0, rate = rate,
                startedAt = System.currentTimeMillis(), error = null
            )
            if (state.value.ready) {
                applyVoiceConfig(locale, rate)
                playCurrent()
            }
            // else: onInit() picks the queue up when the engine is ready.
        }
    }

    fun pause() {
        // tts.stop() drops the queued chunks; resume() re-enqueues from the
        // chunk that was playing (chunkIdx), so position survives a pause.
        tts?.stop()
        neural?.stopCurrent()
        // activeText survives so the highlight stays where the voice stopped.
        beginSpeech()
        publish { it.copy(isPlaying = false) }
    }

    fun resume() {
        if (state.value.queue.isEmpty()) return
        if (chunks.isNotEmpty() && chunkIdx in chunks.indices) speakChunksFrom(chunkIdx)
        else playCurrent()
    }

    fun stop() {
        tts?.stop()
        neural?.stopCurrent()
        beginSpeech()
        clearChunks()
        publish {
            it.copy(
                isPlaying = false, activeUtterance = null, activeText = "", activeTextPreview = "",
                queue = emptyList(), cursor = 0
            )
        }
    }

    fun next() {
        val s = state.value
        if (s.queue.isEmpty()) return
        publish { it.copy(cursor = Math.min(it.cursor + 1, it.queue.size - 1)) }
        playCurrent()
    }

    fun prev() {
        val s = state.value
        if (s.queue.isEmpty()) return
        publish { it.copy(cursor = Math.max(it.cursor - 1, 0)) }
        playCurrent()
    }

    fun setRate(r: Float) {
        publish { it.copy(rate = r) }
        tts?.setSpeechRate(r)
        neural?.setRate(r)
    }

    /**
     * Speak a single utterance (not tied to a document in the library) —
     * used by the in-car "ask" and "figures" spoken surfaces.
     */
    fun sayOnce(text: String) {
        enqueueSay(text, flush = true)
    }

    /**
     * Voice loop v2: progressively speak streamed sentences. flush=true on
     * the first sentence of a reply (interrupts whatever was playing);
     * subsequent sentences QUEUE_ADD behind it. Safe to call from any
     * thread (hops to the main-thread scope).
     *
     * Returns the utterance id it will be spoken under, minted before any
     * scheduling so the caller can map id → sentence for highlighting the
     * moment this returns.
     */
    fun enqueueSay(text: String, flush: Boolean): String {
        val id = UUID.randomUUID().toString()
        if (text.isBlank()) return id
        scope.launch {
            requestSpeech(flush) {
                clearChunks()   // sayOnce mode: no doc-advance on done
                if (flush) { beginSpeech(); publishSpeaking(id, text) }
                engineSpeak(text, id, flush)
            }
        }
        return id
    }

    /**
     * Speak [sentences] as a chain of individual utterances, returning their
     * ids in order (synchronously, before the first one starts).
     *
     * A chain rather than one joined utterance because each utterance reports
     * its own start — that is what the read-along highlight follows — and
     * because a rate change only reaches utterances not yet synthesised, so a
     * giant utterance used to ignore a speed chip for a minute (DESIGN §6.6).
     *
     * [onDone] runs at most once, on the player's main-thread scope, when the
     * last sentence finishes NATURALLY. Anything that supersedes the chain —
     * sayOnce, a flushing enqueueSay, another sayChain, play, pause, stop, or
     * an engine error — cancels it silently. Blank entries are dropped; with
     * nothing left to say the result is empty and [onDone] never runs.
     */
    fun sayChain(
        sentences: List<String>,
        flush: Boolean = true,
        onDone: (() -> Unit)? = null
    ): List<String> {
        val items = sentences.map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return emptyList()
        val ids = List(items.size) { UUID.randomUUID().toString() }
        scope.launch {
            requestSpeech(flush) {
                clearChunks()   // sayOnce mode: no doc-advance on done
                if (flush) { beginSpeech(); publishSpeaking(ids[0], items[0]) }
                book.armChain(ids, onDone)
                items.forEachIndexed { i, s -> engineSpeak(s, ids[i], flush = flush && i == 0) }
            }
        }
        return ids
    }

    // ------------------------------------------------------------------
    // Internals

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setOnUtteranceProgressListener(utteranceListener)
            publish { it.copy(ready = true) }
            // Replay whatever was requested while the engine was initialising.
            scope.launch {
                applyVoiceConfig("en-US", state.value.rate)
                if (pendingSpeech.isNotEmpty()) {
                    val queued = pendingSpeech.toList()
                    pendingSpeech.clear()
                    queued.forEach { it() }
                } else if (state.value.queue.isNotEmpty()) playCurrent()
            }
        } else {
            publish { it.copy(ready = false, error = "TTS init failed: $status") }
        }
    }

    private fun initIfNeeded() {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    private fun applyVoiceConfig(locale: String, rate: Float) {
        val loc = if (locale.contains("-")) {
            val (l, c) = locale.split("-", limit = 2)
            Locale(l, c)
        } else Locale.forLanguageTag(locale)
        tts?.language = loc
        tts?.setSpeechRate(rate)
    }

    private fun playCurrent() {
        val s = state.value
        if (s.queue.isEmpty() || !s.ready) { publish { it.copy(isPlaying = false) } ; return }
        val id = s.queue.getOrNull(s.cursor) ?: return
        val doc = library().firstOrNull { it.id == id }
        val text = doc?.text ?: ""
        if (text.isBlank()) {
            // Can't read empty text (e.g. scanned PDF); move on so we don't wedge.
            next(); return
        }
        chunks = chunkText(text)
        chunkIds = chunks.map { UUID.randomUUID().toString() }
        chunkIdx = 0
        speakChunksFrom(0)
    }

    private fun speakChunksFrom(start: Int) {
        if (tts == null && neural == null) return
        if (chunks.isEmpty() || start !in chunks.indices) return
        beginSpeech()
        finalUtteranceOfDoc = chunkIds.last()
        publishSpeaking(chunkIds[start], chunks[start])
        for (i in start until chunks.size) {
            engineSpeak(chunks[i], chunkIds[i], flush = i == start)
        }
    }

    private suspend fun advanceDoc() {
        val s = state.value
        val nextIdx = s.cursor + 1
        if (nextIdx >= s.queue.size) {
            clearChunks()
            publishIdle()
        } else {
            publish { it.copy(cursor = nextIdx) }
            playCurrent()
        }
    }

    /**
     * Runs [block] now, or parks it for [onInit] to replay. A flushing request
     * drops anything parked ahead of it — it would have superseded it anyway.
     */
    private fun requestSpeech(flush: Boolean, block: () -> Unit) {
        initIfNeeded()
        if (state.value.ready) block()
        else {
            if (flush) pendingSpeech.clear()
            pendingSpeech.add(block)
        }
    }

    /**
     * Marks everything in flight stale, so no straggling callback can act on
     * it — and drops anything still parked, which would otherwise speak when
     * the engine finally comes up, after the user asked for something else.
     */
    private fun beginSpeech() {
        book.interrupt()
        pendingSpeech.clear()
    }

    private fun publishSpeaking(id: String, text: String) {
        publish {
            it.copy(
                activeUtterance = id, activeText = text,
                activeTextPreview = truncate(text, PREVIEW_CHARS)
            )
        }
    }

    private fun publishIdle() {
        publish {
            it.copy(isPlaying = false, activeUtterance = null, activeText = "", activeTextPreview = "")
        }
    }

    private fun clearChunks() {
        chunks = emptyList()
        chunkIds = emptyList()
        chunkIdx = 0
        finalUtteranceOfDoc = null
    }

    /**
     * Split text into sentence-aligned chunks of at most [maxLen] chars.
     * (TTS's own cap is getMaxSpeechInputLength() ≈ 4000; we stay well under
     * it so pause/resume granularity is a few sentences, not a whole page.)
     */
    private fun chunkText(text: String, maxLen: Int = 600): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (raw in text.split(Regex("(?<=[.!?])\\s+"))) {
            var s = raw.trim()
            if (s.isEmpty()) continue
            while (s.length > maxLen) {   // pathological: no sentence breaks
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                val cut = s.lastIndexOf(' ', maxLen).let { if (it > 0) it else maxLen }
                out.add(s.substring(0, cut).trim())
                s = s.substring(cut).trim()
            }
            if (s.isEmpty()) continue
            if (sb.isEmpty() || sb.length + 1 + s.length <= maxLen) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            } else {
                out.add(sb.toString()); sb.clear(); sb.append(s)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private inline fun publish(mutate: (PlaybackState) -> PlaybackState) {
        state.value = mutate(state.value)
    }

    companion object {
        private const val PREVIEW_CHARS = 96
        private fun truncate(s: String, n: Int) = if (s.length <= n) s else s.substring(0, n) + "…"
    }
}

/**
 * Public playback state. Immutable and safe to observe via [AudiobookPlayer.stateFlow].
 */
data class PlaybackState(
    val ready: Boolean = false,
    val isPlaying: Boolean = false,
    val queue: List<String> = emptyList(),
    val cursor: Int = 0,
    val rate: Float = 1.0f,
    val activeUtterance: String? = null,
    /** FULL text of the utterance now speaking; empty when idle. Drives the read-along highlight. */
    val activeText: String = "",
    val activeTextPreview: String = "",
    val startedAt: Long = 0L,
    val error: String? = null
) {
    val currentIndexTitle: String? get() = queue.getOrNull(cursor)
}
