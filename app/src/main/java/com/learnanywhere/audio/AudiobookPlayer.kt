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
    /** sayOnce()/enqueueSay() texts that arrived before the engine was ready. */
    private val pendingSay = ArrayList<String>()

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val i = chunkIds.indexOf(utteranceId)
            if (i >= 0) chunkIdx = i
            publish { it.copy(isPlaying = true, activeUtterance = utteranceId) }
        }
        override fun onDone(utteranceId: String?) {
            val finalId = finalUtteranceOfDoc
            if (finalId != null) {
                if (utteranceId == finalId) scope.launch { advanceDoc() }
                // else: an intermediate chunk finished; the next queued chunk
                // fires onStart on its own.
            } else if (utteranceId == state.value.activeUtterance) {
                // sayOnce finished.
                publish { it.copy(isPlaying = false, activeUtterance = null) }
            }
        }
        override fun onError(utteranceId: String?) {
            publish { it.copy(isPlaying = false, activeUtterance = null, error = "TTS error") }
        }
        override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
    }

    /** Public surface driven by both phone UI and car UI. */
    fun play(docIds: List<String>, rate: Float = 1.0f, locale: String = "en-US") {
        scope.launch {
            initIfNeeded()
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
        publish { it.copy(isPlaying = false) }
    }

    fun resume() {
        if (state.value.queue.isEmpty()) return
        if (chunks.isNotEmpty() && chunkIdx in chunks.indices) speakChunksFrom(chunkIdx)
        else playCurrent()
    }

    fun stop() {
        tts?.stop()
        clearChunks()
        publish { it.copy(isPlaying = false, activeUtterance = null, queue = emptyList(), cursor = 0) }
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
    }

    /**
     * Speak a single utterance (not tied to a document in the library) —
     * used by the in-car "ask" and "figures" spoken surfaces.
     */
    fun sayOnce(text: String) {
        scope.launch {
            initIfNeeded()
            if (state.value.ready) speakSingle(text)
            else { pendingSay.clear(); pendingSay.add(text) }
        }
    }

    /**
     * Voice loop v2: progressively speak streamed sentences. flush=true on
     * the first sentence of a reply (interrupts whatever was playing);
     * subsequent sentences QUEUE_ADD behind it. Safe to call from any
     * thread (hops to the main-thread scope).
     */
    fun enqueueSay(text: String, flush: Boolean) {
        if (text.isBlank()) return
        scope.launch {
            initIfNeeded()
            if (!state.value.ready) {
                if (flush) pendingSay.clear()
                pendingSay.add(text)
                return@launch
            }
            clearChunks()   // sayOnce mode: no doc-advance on done
            val id = UUID.randomUUID().toString()
            publish { it.copy(activeUtterance = id, activeTextPreview = truncate(text, 96)) }
            try {
                tts?.speak(text,
                    if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null, id)
            } catch (t: Throwable) {
                publish { it.copy(error = t.message) }
            }
        }
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
                if (pendingSay.isNotEmpty()) {
                    val queued = pendingSay.toList()
                    pendingSay.clear()
                    queued.forEachIndexed { i, s ->
                        if (i == 0) speakSingle(s)
                        else tts?.speak(s, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
                    }
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
        val t = tts ?: return
        if (chunks.isEmpty() || start !in chunks.indices) return
        finalUtteranceOfDoc = chunkIds.last()
        publish { it.copy(activeUtterance = chunkIds[start],
                          activeTextPreview = truncate(chunks[start], 96)) }
        try {
            for (i in start until chunks.size) {
                t.speak(chunks[i],
                    if (i == start) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null, chunkIds[i])
            }
        } catch (e: Throwable) {
            publish { it.copy(error = e.message) }
        }
    }

    private suspend fun advanceDoc() {
        val s = state.value
        val nextIdx = s.cursor + 1
        if (nextIdx >= s.queue.size) {
            clearChunks()
            publish { it.copy(isPlaying = false, activeUtterance = null) }
        } else {
            publish { it.copy(cursor = nextIdx) }
            playCurrent()
        }
    }

    /** Speak one stand-alone utterance (sayOnce mode; no doc-advance on done). */
    private fun speakSingle(text: String) {
        if (tts == null) return
        clearChunks()
        val id = UUID.randomUUID().toString()
        publish { it.copy(activeUtterance = id, activeTextPreview = truncate(text, 96)) }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (t: Throwable) {
            publish { it.copy(error = t.message) }
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
    val activeTextPreview: String = "",
    val startedAt: Long = 0L,
    val error: String? = null
) {
    val currentIndexTitle: String? get() = queue.getOrNull(cursor)
}
