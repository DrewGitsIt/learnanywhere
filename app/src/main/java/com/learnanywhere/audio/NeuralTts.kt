package com.learnanywhere.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Piper neural voice (DESIGN roadmap #9) via sherpa-onnx OfflineTts
 * (vits-piper-en_US-amy-medium, ~65 MB, bundled in assets/tts/). Fully
 * on-device. Mirrors TextToSpeech's utterance semantics so AudiobookPlayer
 * can drive either engine: [onStart]/[onDone] per utterance id, [onFailed]
 * for permanent failure (player falls back to the system voice).
 *
 * espeak-ng-data must live on the real filesystem (not inside the APK), so
 * it's copied out of assets to filesDir on first load.
 */
class NeuralTts(private val context: Context) {

    var onStart: ((String) -> Unit)? = null
    var onDone: ((String) -> Unit)? = null
    /** Called once if the engine can't run; includes the text that failed. */
    var onFailed: ((id: String, text: String, error: String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tts: OfflineTts? = null
    private var worker: Job? = null
    private val queue = LinkedBlockingQueue<Pair<String, String>>()
    private var track: AudioTrack? = null
    @Volatile private var speed = 1.0f
    @Volatile private var generation = 0   // bumped by stopCurrent(); stales old work

    fun setRate(r: Float) { speed = r }

    /** Queue an utterance; flush interrupts and clears everything queued. */
    fun enqueue(id: String, text: String, flush: Boolean) {
        if (flush) stopCurrent()
        queue.put(id to text)
        if (worker?.isActive != true) {
            val gen = generation
            worker = scope.launch { runLoop(gen) }
        }
    }

    /** Stop playback immediately and drop the queue. */
    fun stopCurrent() {
        generation++
        queue.clear()
        try { track?.pause(); track?.flush() } catch (_: Throwable) {}
    }

    fun release() {
        stopCurrent()
        tts?.let { it.release() }
        tts = null
    }

    // ------------------------------------------------------------------

    private fun runLoop(gen: Int) {
        val engine = try {
            ensureLoaded()
        } catch (t: Throwable) {
            val (id, text) = queue.poll() ?: ("" to "")
            queue.clear()
            onFailed?.invoke(id, text, "Piper init failed: ${t.message}")
            return
        }
        while (gen == generation) {
            val item = queue.poll() ?: break
            val (id, text) = item
            try {
                onStart?.invoke(id)
                // Piper reads speed as length-scale inverse: generate(speed=rate).
                val audio = engine.generate(text, 0, speed)
                if (gen != generation) break
                playBlocking(audio.samples, audio.sampleRate, gen)
                if (gen != generation) break
                onDone?.invoke(id)
            } catch (t: Throwable) {
                onFailed?.invoke(id, text, "Piper synthesis failed: ${t.message}")
                queue.clear()
                return
            }
        }
    }

    @Synchronized
    private fun ensureLoaded(): OfflineTts {
        tts?.let { return it }
        val dataDir = copyAssetDir("tts/espeak-ng-data", File(context.filesDir, "espeak-ng-data"))
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "tts/en_US-amy-medium.onnx",
                    lexicon = "",
                    tokens = "tts/tokens.txt",
                    dataDir = dataDir.absolutePath
                ),
                numThreads = 2
            )
        )
        return OfflineTts(context.assets, config).also { tts = it }
    }

    /** Recursive assets → filesystem copy (idempotent via a marker file). */
    private fun copyAssetDir(assetPath: String, dest: File): File {
        val marker = File(dest, ".complete")
        if (marker.exists()) return dest
        dest.mkdirs()
        val names = context.assets.list(assetPath) ?: emptyArray()
        for (name in names) {
            val childAsset = "$assetPath/$name"
            val children = context.assets.list(childAsset)
            if (children.isNullOrEmpty()) {
                context.assets.open(childAsset).use { ins ->
                    File(dest, name).outputStream().use { ins.copyTo(it) }
                }
            } else {
                copyAssetDir(childAsset, File(dest, name))
            }
        }
        marker.writeText("ok")
        return dest
    }

    /** Play float PCM and wait for it to drain (interruptible via generation). */
    private fun playBlocking(samples: FloatArray, sampleRate: Int, gen: Int) {
        if (samples.isEmpty()) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBuf, 8192), AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
        track = t
        try {
            t.play()
            var off = 0
            while (off < samples.size && gen == generation) {
                val n = minOf(4096, samples.size - off)
                val written = t.write(samples, off, n, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                off += written
            }
            // Drain: wait until the head reaches what we wrote (or interrupt).
            if (gen == generation) {
                val totalFrames = off
                var waited = 0
                while (gen == generation && t.playbackHeadPosition < totalFrames && waited < 30_000) {
                    Thread.sleep(40); waited += 40
                }
            }
        } finally {
            try { t.stop() } catch (_: Throwable) {}
            t.release()
            if (track === t) track = null
        }
    }
}
