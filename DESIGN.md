# LearnAnywhere — design & direction

*Updated 2026-09-20. This is the product-direction doc; STATUS.md tracks build
state; README.md describes what's implemented today.*

## 1. Vision

A hands-free study companion. I say I have documents — local (on the phone) or
remote (I know they exist on the internet) — and the agent reads through them
*with* me: providing context, answering questions as we go, and looking up
ancillary information from the web when needed. Interaction is **entirely
vocal**; a visual surface **mirrors** the agent — the words it's speaking plus
any images/figures it references — for when I can glance at the screen.

Constraints:
- **Free**: built on freely available AI (Gemini free tier, etc.), no paid API.
- **Voice privacy**: my raw voice audio must not go to Google (or any cloud).
  Document content is a separate, weaker constraint (see §3.3).

## 2. Where the code is today vs. the vision

| Vision piece | Today | Gap |
|---|---|---|
| Speech **input** (ask by voice) | ❌ none — questions are typed | The biggest gap. See §3.1 — fully solvable locally. |
| Speech **output** | ✅ Android `TextToSpeech` (on-device) | Works; robotic. Neural upgrade path in §3.2. |
| Conversational doc Q&A | ✅ Gemini + inline PDF grounding, single-turn | No multi-turn history; each `ask()` is stateless. |
| "Read the doc *with* me" | ⚠️ audiobook reads raw text; agent is separate | No interleaved read-a-section → discuss → continue loop. PDF text extraction returns `""`, so PDFs can't be read aloud at all yet. |
| Web lookup for ancillary info | ❌ | Gemini free tier includes Google Search grounding (§3.4) — just a request flag. |
| Remote documents ("I know it exists") | ⚠️ URL fetch of a known URL | No "find the paper by name" flow; search grounding can bridge. |
| Visual mirror (verbiage + images) | ⚠️ static reply card + PDF-page figures | No live transcript/read-along highlight; figure extraction is a page-level heuristic. |
| In-car | ✅ MediaBrowserService (now correctly declared) | Fine as-is for audio; voice loop matters more. |

## 3. Areas of uncertainty — investigated 2026-09-20

### 3.1 Local ASR — **yes, and without Google**

The answer to "can we do local ASR because I don't want Google to use my
voice?" is **yes, comfortably, on-CPU, with permissively-licensed open
models**:

- **Recommended stack: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**
  (Apache-2.0, one prebuilt Android AAR, official Kotlin API, no JNI to write).
  One library provides:
  - **Silero VAD** (~2 MB, MIT) as the always-listening gate;
  - **streaming zipformer** English ASR (`sherpa-onnx-streaming-zipformer-en-2023-06-26`,
    int8 ≈ 70 MB, RTF ≈ 0.06 — real-time with live partial results, built-in
    endpointing for utterance segmentation);
  - optional **KeywordSpotter** for a spoken trigger word.
  Runs 100% offline; nothing leaves the device.
- **Optional two-pass accuracy upgrade**: stream zipformer for live partials,
  re-decode the finished utterance with **Moonshine** (MIT, 27–61M params,
  beats Whisper-tiny/base; sherpa-onnx runs it) or whisper.cpp small. Probably
  unnecessary for short spoken questions.
- **Do NOT build on Android's `SpeechRecognizer`**, even
  `createOnDeviceSpeechRecognizer()` (API 33+): it's Pixel-gated for quality,
  runs in Google's closed-source app (federated telemetry still flows to
  Google), and is explicitly not designed for continuous dictation
  (~5 s-silence session ends; `EXTRA_SEGMENTED_SESSION` is optional per
  engine).
- **Wake words**: Porcupine's free tier was discontinued June 2026 — avoid.
  Practical hands-free pattern: VAD-gated always-listening while the app is in
  a session (mic foreground-service type + persistent notification on
  Android 14), with barge-in (§3.5). openWakeWord models are CC-BY-NC-SA —
  fine for a personal app if a real wake word is ever wanted.
- **Costs**: a few %/hour battery with VAD gating; car cabin noise degrades
  accuracy (the two-pass upgrade is the mitigation); no punctuation from the
  streaming model unless you add sherpa-onnx's punctuation model.

### 3.2 Voice output — keep on-device; optional neural upgrade

- Android's built-in TTS **is on-device** once an offline voice pack is
  installed — the current fallback stays free and private. Quality is the only
  complaint.
- Upgrade path, same sherpa-onnx AAR as the ASR: **Piper** voices
  (~63 MB medium, sub-200 ms to first audio — best for continuous reading) and
  **Kokoro-82M int8** (~80–90 MB, best quality, ~0.5–2 s first-audio — viable
  if you synthesize sentence N+1 while N plays). License note: espeak-ng G2P
  in some pipelines is GPL; irrelevant for a personal app, and sherpa-onnx
  ships dictionary phonemizers anyway.
- Gemini's native TTS / Live API exist on the free tier but free quotas are
  tiny/unpublished and it sends content to Google — not the primary path.

### 3.3 Free LLM backend — Gemini stays, with eyes open

- **Free tier data use (the catch)**: per Google's terms (updated 2026-04-28),
  unpaid-tier prompts *and attached documents* "are used to provide, improve,
  and develop Google products … and machine learning technologies" and **may
  be human-reviewed**. Exception: EEA/UK/Switzerland users get paid-tier data
  terms even on free. So: *voice stays local (§3.1); documents sent to Gemini
  are training data*. Fine for arXiv papers and Wikipedia; not for anything
  sensitive. A paid key (any billing tier) flips to "not used for training."
- **Quotas are volatile**: Google cut free quotas ~50–80% in Dec 2025 without
  announcement and no longer publishes a per-model table — read live limits at
  https://aistudio.google.com/rate-limit. Plan for ~10 RPM, low-hundreds
  requests/day, Flash-only (Pro reportedly left the free tier spring 2026).
  Current free models include Gemini 3.x Flash / Flash-Lite and 2.5 Flash;
  **2.0 Flash was retired 2026-03**. Keep the model id user-configurable (it
  already is).
- **Inline PDF cap is 20 MB total request** (not 50 MB — that's the free Files
  API, up to 1,000 pages, 48 h retention). Worth adding a Files-API path for
  big documents.
- **Native audio input is free** — Gemini accepts audio directly. Deliberately
  unused: it would send voice to Google. Local ASR → text keeps the privacy
  boundary clean.
- **Privacy-respecting fallback**: **Groq** free tier (no card; Llama/Qwen
  models; contractually does not train on or retain inputs) — the best "my
  document is sensitive" free option, paired with local extraction since it
  has no PDF ingestion. Worth a provider abstraction eventually; not urgent.
- **Non-options confirmed**: OpenAI has no free API tier (except an explicit
  train-on-your-data program); driving the consumer ChatGPT/Gemini apps
  programmatically violates both companies' ToS.

### 3.4 Web lookup — free, already in the API

Google Search grounding is included in the Gemini free tier: Gemini 3.x models
share **5,000 grounded requests/month** (2.5: 500/day). It's a tool flag on
`generateContent` plus citation metadata in the response — this is the
"look up ancillary information" feature, essentially free to add.

### 3.5 The voice loop (architecture for the vocal interface)

```
mic (16 kHz, foreground service)
  → Silero VAD (gate; also fires barge-in)
  → streaming zipformer ASR (live partials → visual mirror)
  → endpoint → question text
  → Gemini generateContent (docs grounding + search grounding, streamed)
  → sentence-splitter on the token stream
  → TTS queue (Android TTS now; Piper later), sentence-chunked
  → visual mirror highlights the sentence being spoken; figures render inline
```
- **Barge-in**: VAD fires while TTS is playing → stop playback, flush queued
  audio, cancel in-flight LLM/TTS, start a fresh ASR pass. Keeping the mic
  open during playback needs echo cancellation
  (`AudioSource.VOICE_COMMUNICATION` / `AcousticEchoCanceler`) — quality
  varies by device; fall back to pause-to-speak if AEC is poor.
- **Car audio**: `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`, `AUDIOFOCUS_GAIN`;
  pause (don't duck) on focus loss. Play over **A2DP**; record via the
  *phone's* mic — never Bluetooth SCO (it tears down A2DP and drops to
  telephony quality).
- **"Read with me" mode**: alternate `read next section aloud` (local TTS of
  extracted text) with open mic for questions; the agent call carries the
  conversation history plus a cursor ("we are in §3.2 of <doc>").

### 3.6 Still uncertain (needs hands-on verification)

1. ~~The live `Test connection` 4xx~~ — **resolved 2026-09-21**: new API keys
   can't use `gemini-2.5-flash` at all (404 "no longer available to new
   users"); default is now `gemini-3.6-flash`. Also: Gemini 3.x thinking
   tokens draw from `maxOutputTokens` — small budgets return empty text
   (STATUS.md #7).
2. Free-tier RPM/RPD as they apply to *this* key — read from AI Studio.
3. Streaming-zipformer accuracy against real car-cabin audio on my device.
4. Whether device AEC is good enough for barge-in, per-device.
5. PDF text-layer extraction quality (pdfbox-android) on real papers —
   determines how good "read the paper aloud" can be without cloud help.

## 4. Roadmap (proposed order)

1. **Fix the live Gemini 4xx** (error text now readable) and verify the fixes
   in STATUS.md on device.
2. ~~PDF text extraction~~ — **done 2026-09-21** (pdfbox-android 2.0.27.0,
   `data/PdfText.kt`, add-time extraction + cold-start backfill). Scanned
   PDFs remain text-less (would need OCR — ML Kit on-device is an option).
3. **Voice input v1** — **push-to-talk shipped 2026-09-21**
   (`speech/VoiceInput.kt`: sherpa-onnx 1.13.8 AAR + streaming zipformer
   int8 from assets; live partials mirrored into the Ask field; endpoint
   rules auto-ask when you stop talking; audio never leaves the device).
   Large binaries are gitignored — run `scripts/fetch_speech_assets.sh`
   after cloning. Remaining for v1.5: VAD-gated continuous listening +
   mic foreground service, punctuation model.
4. **Conversation history** in `LearnAnywhereAgent` (multi-turn `contents`)
   + **search grounding** flag with rendered citations.
5. **Voice loop v2**: streamed Gemini responses → sentence-chunked TTS,
   read-along highlight, barge-in.
6. **"Read with me" mode** interleaving section reading and discussion.
7. **Neural TTS** (Piper via sherpa-onnx) as an optional voice.
8. Later: Files API for >20 MB PDFs, Groq fallback provider, real figure
   extraction (vector-native), richer AA surface.

## 5. Decisions log

- **Voice never leaves the device** — local ASR + local TTS, even though
  Gemini's free native-audio path exists. (2026-09-20)
- **Documents may go to Gemini free tier**, accepting the training-data terms
  for non-sensitive material; sensitive docs wait for a paid key or Groq path.
  (2026-09-20)
- **No consumer-app scraping** (ChatGPT/Gemini web) — ToS violation, fragile.
  API free tiers only. (2026-09-20)
- **sherpa-onnx as the single on-device speech dependency** (VAD + ASR + later
  TTS) rather than separate whisper.cpp/Vosk/Piper integrations. (2026-09-20)
