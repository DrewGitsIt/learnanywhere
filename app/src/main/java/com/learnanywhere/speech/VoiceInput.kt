package com.learnanywhere.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local, on-device speech input (DESIGN.md §3.1). Nothing leaves the phone:
 * sherpa-onnx streaming zipformer (int8, bundled in assets/asr/) decodes the
 * mic in real time on the CPU.
 *
 * v1 is push-to-talk with auto-finish: [start] opens the mic and streams
 * partial transcripts into [partial]; when the recognizer's endpoint rules
 * detect the utterance is over (or [stop] is tapped), the final text is
 * delivered via onFinal and the session ends. Continuous VAD-gated listening
 * and barge-in are the next increment.
 */
class VoiceInput(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    enum class State { IDLE, LOADING, LISTENING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> get() = _state.asStateFlow()

    /** Live partial transcript while listening. */
    val partial = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)

    private var recognizer: OnlineRecognizer? = null
    private var sessionJob: Job? = null
    @Volatile private var stopRequested = false

    /** Caller must hold RECORD_AUDIO permission before calling. */
    fun start(onFinal: (String) -> Unit) {
        if (sessionJob?.isActive == true) return
        stopRequested = false
        error.value = null
        sessionJob = scope.launch(Dispatchers.IO) { runSession(onFinal) }
    }

    /** Finish early: whatever has been recognized so far becomes the final text. */
    fun stop() {
        stopRequested = true
    }

    // ------------------------------------------------------------------

    /**
     * First call builds the recognizer from assets (~70 MB encoder; a few
     * seconds on first use). Kept loaded for the rest of the process.
     */
    private fun ensureRecognizer(): OnlineRecognizer {
        recognizer?.let { return it }
        _state.value = State.LOADING
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "asr/encoder.int8.onnx",
                    decoder = "asr/decoder.int8.onnx",
                    joiner = "asr/joiner.int8.onnx",
                ),
                tokens = "asr/tokens.txt",
                modelType = "zipformer",
                numThreads = 2,
            ),
            endpointConfig = EndpointConfig(
                // rule1: long trailing silence even with no speech yet.
                rule1 = EndpointRule(false, 2.4f, 0f),
                // rule2: shorter trailing silence once something was said.
                rule2 = EndpointRule(true, 1.2f, 0f),
                // rule3: hard cap on utterance length (seconds).
                rule3 = EndpointRule(false, 0f, 30f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(context.assets, config).also { recognizer = it }
    }

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    private suspend fun runSession(onFinal: (String) -> Unit) {
        var record: AudioRecord? = null
        try {
            val rec = ensureRecognizer()
            val stream = rec.createStream()
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, SAMPLE_RATE)) // >= 0.5 s of 16-bit samples
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    error.value = "Microphone unavailable"
                    return
                }
                record.startRecording()
                partial.value = ""
                _state.value = State.LISTENING

                val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms chunks
                var finalText = ""
                while (!stopRequested) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buf[it] / 32768f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (rec.isReady(stream)) rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    if (text != partial.value) partial.value = text
                    if (rec.isEndpoint(stream)) {
                        if (text.isNotBlank()) { finalText = text; break }
                        rec.reset(stream) // silence-only endpoint: keep waiting
                    }
                }
                if (finalText.isBlank()) finalText = rec.getResult(stream).text.trim()
                if (finalText.isNotBlank()) {
                    withContext(Dispatchers.Main) { onFinal(finalText) }
                }
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            error.value = "Voice input failed: ${t.message}"
        } finally {
            try { record?.stop() } catch (_: Throwable) {}
            record?.release()
            _state.value = State.IDLE
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
