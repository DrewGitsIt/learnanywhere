package com.learnanywhere.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Barge-in (voice loop v2, DESIGN §3.5): while TTS is speaking, watch the mic
 * with Silero VAD (on-device, ~2 MB). When real speech is detected, fire
 * [onSpeech] so the caller can pause playback and open the recognizer.
 *
 * Echo mitigation: records from VOICE_COMMUNICATION (AEC-preferred source)
 * and attaches AcousticEchoCanceler when the device provides one; the VAD
 * threshold is raised above default so residual TTS bleed-through doesn't
 * trigger. Device AEC quality varies — DESIGN lists pause-to-talk as the
 * fallback if a given phone self-triggers.
 */
class BargeInGuard(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private var vad: Vad? = null
    private var job: Job? = null

    /**
     * Serializes every native VAD call. Streaming TTS flaps playback state at
     * each sentence boundary, so stop()/start() pairs arrive in quick
     * succession — and cancellation is cooperative, so a dying loop can
     * overlap its replacement by an iteration. Two threads inside
     * Vad.acceptWaveform on the SAME native object is a use-after-free
     * SIGSEGV (observed live 2026-09-21, crash in libsherpa-onnx-jni memcpy).
     */
    private val vadLock = Any()

    val running: Boolean get() = job?.isActive == true

    /** Caller must hold RECORD_AUDIO. Idempotent while running. */
    fun start(onSpeech: () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run(onSpeech) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun ensureVad(): Vad = synchronized(vadLock) {
        vad?.let { it.clear(); return it }
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "asr/silero_vad.onnx",
                threshold = 0.6f,             // above default: ignore TTS bleed
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.3f,     // demand a third of a second of speech
                windowSize = 512,
                maxSpeechDuration = 5f
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1
        )
        return Vad(context.assets, config).also { vad = it }
    }

    @SuppressLint("MissingPermission")
    private suspend fun run(onSpeech: () -> Unit) {
        var record: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        try {
            val v = ensureVad()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE / 2))
            if (record.state != AudioRecord.STATE_INITIALIZED) return
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            }
            record.startRecording()
            val buf = ShortArray(512)   // one VAD window
            // Check OUR OWN coroutine, never the shared job field: after a
            // quick stop()/start() the field holds the REPLACEMENT job, and
            // the old loop reading it would happily run forever alongside
            // the new one.
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { buf[it] / 32768f }
                val speech = synchronized(vadLock) {
                    v.acceptWaveform(samples)
                    v.isSpeechDetected().also { if (it) v.clear() }
                }
                if (speech) {
                    withContext(Dispatchers.Main) { onSpeech() }
                    break
                }
            }
        } catch (_: Throwable) {
            // Barge-in is best-effort; never let it crash playback.
        } finally {
            try { record?.stop() } catch (_: Throwable) {}
            record?.release()
            try { aec?.release() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}
