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
 * On Android 14 this maps naturally to an AndroidX MediaSession so the car
 * head unit sees it as any other media app. (The companion MediaActivity in
 * `com.learnanywhere.car` is the glue.)
 */
class AudiobookPlayer(
    private val context: Context,
    private val library: () -> List<Document>
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val state = MutableStateFlow(PlaybackState())
    val stateFlow: StateFlow<PlaybackState> get() = state.asStateFlow()

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            publish { it.copy(isPlaying = true, activeUtterance = utteranceId) }
        }
        override fun onDone(utteranceId: String?) {
            if (utteranceId == state.value.activeUtterance) {
                scope.launch { advanceDoc() }
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
            val loc = if (locale.contains("-")) {
                val (l, c) = locale.split("-", limit = 2)
                Locale(l, c)
            } else Locale.forLanguageTag(locale)
            tts?.language = loc
            tts?.setSpeechRate(rate)
            state.value = state.value.copy(
                queue = docIds, cursor = 0, rate = rate,
                startedAt = System.currentTimeMillis(), error = null
            )
            playCurrent()
        }
    }

    fun pause() {
        tts?.stop()
        publish { it.copy(isPlaying = false) }
    }

    fun resume() {
        if (state.value.queue.isEmpty()) return
        playCurrent()
    }

    fun stop() {
        tts?.stop()
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
     * Speak a single sentence (not tied to a document in the library) —
     * used by the in-car "ask" and "figures" spoken surfaces.
     */
    fun sayOnce(text: String) {
        initIfNeeded()
        speak(text)
    }

    // ------------------------------------------------------------------
    // Internals

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setOnUtteranceProgressListener(utteranceListener)
            publish { it.copy(ready = true) }
        } else {
            publish { it.copy(ready = false, error = "TTS init failed: $status") }
        }
    }

    private fun initIfNeeded() {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
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
        speak(firstUtterance(text))
    }

    private suspend fun advanceDoc() {
        val s = state.value
        val nextIdx = s.cursor + 1
        if (nextIdx >= s.queue.size) {
            publish { it.copy(isPlaying = false, activeUtterance = null) }
        } else {
            publish { it.copy(cursor = nextIdx) }
            playCurrent()
        }
    }

    private fun speak(text: String) {
        if (tts == null) return
        val id = UUID.randomUUID().toString()
        publish { it.copy(activeUtterance = id, activeTextPreview = truncate(text, 96)) }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (t: Throwable) {
            publish { it.copy(error = t.message) }
        }
    }

    /**
     * For the MVP we speak the first ~700 chars per utterance. A production
     * app would enqueue every chunk in order and let TTS play them one by one
     * (TTS already handles multi-utterance queues via `QUEUE_ADD`).
     */
    private fun firstUtterance(text: String): String {
        val n = text.length.coerceAtMost(700)
        val end = if (n >= text.length) text.length
        else {
            val sub = text.substring(0, n)
            val k = sub.lastIndexOf(' ')
            if (k > 0) k else n
        }
        return text.substring(0, end).trim()
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
