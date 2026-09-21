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

### 3.7 Agent architecture review (researched 2026-09-21)

Drew's observation: this is a small **research agent** with free Gemini as
the reasoning model — so audit it against agent-design practice. Findings
(all verified against ai.google.dev unless noted):

**Framework: hand-rolled is correct at this scale.** Native Android apps in
2026 either hand-roll against REST or use Google's Gen AI Kotlin SDK
(`com.google.genai:google-genai-kotlin:1.0.0` — would replace our JSON
boilerplate, but our pure body-builder is unit-tested and free-tier-tuned,
so raw OkHttp stays). langchain4j isn't Android-targeted; LangGraph is
Python/JS-only. **JetBrains Koog 1.0** (Kotlin Multiplatform incl. Android,
Gemini support, agent loop + history compression + persistence) is the one
credible framework — the graduation path if the loop outgrows ~3 tools, not
a starting point.

**API surface — the headline**: Google shipped the **Interactions API**
(GA ~June 2026; `POST /v1beta/interactions`), now the *default* surface;
`generateContent` is labeled "legacy" but fully supported. It holds
conversation state server-side (`previous_interaction_id`) — which would
eliminate our per-turn history + document re-send and automate the
thought-signature bookkeeping. Free tier: available, but stored
interactions expire after **1 day** (paid: up to 55 days). *Decision:
stay on generateContent for now; migrate when we build the voice loop
(new models/tools launch on Interactions first), and keep client-side
history as the always-works fallback for the 1-day expiry.*

**Custom tools (for the fetch-document increment)**: Gemini 3 lifts the old
restriction — `google_search` + `functionDeclarations` combine in ONE
request: `toolConfig.includeServerSideToolInvocations: true`, mode
`VALIDATED` (AUTO unsupported in combo), and we MUST round-trip every
part's `thoughtSignature` and each `functionCall.id` or the API errors.
The built-in `url_context` tool may cover "read this URL" without a
custom tool. Loop rules (non-negotiable): hard iteration cap (5–10);
execute ALL parallel functionCalls and return all functionResponses in
one message; tool failures go back to the model as
`functionResponse{error: …}`, never thrown to the user. Tool schemas:
few tools, verb_noun names, descriptions that say when NOT to use them,
enums for closed sets, compact paginated results.

**Context & caching**: implicit caching is automatic and free (min 4,096
tokens on 3.x Flash; stable prefix first — our docs-first ordering is
already cache-aligned). Explicit `cachedContents` is paid-only. The real
fix for re-sending PDF bytes every turn is the **Files API** (free, 48 h
retention, 50 MB/1,000 pages): upload once, reference by
`file_data.file_uri` as the first part each turn. Library economy: as the
library grows, let the model *pull* docs via `search_library`/
`list_library` tools instead of us pushing everything; SQLite FTS5 if
keyword retrieval is ever needed; vector RAG is overkill at ≤ dozens of
docs. History: sliding window is fine for voice sessions; strip old
tool-call payloads, keep final texts.

**Generation config corrections (both classes of bug already bit us)**:
- Thinking: on 3.x use `thinkingConfig.thinkingLevel` (`thinkingBudget`
  is 2.5-era; sending both errors). 3.7/3.8-flash: low/medium/high, no
  full off; 3.6-flash has `minimal`. Thinking tokens count against
  `maxOutputTokens` (our empty-reply bug) → set thinkingLevel low, keep
  maxOutputTokens generous, constrain length via prompt.
- **Temperature: leave at the default 1.0 on Gemini 3** — official docs
  warn lower values cause looping/degradation. We currently send 0.4 →
  change in the hardening pass.

**System prompt**: restructure with delimited sections (Role / Context /
Tools / Output rules / Guardrails); grounding rules belong in
systemInstruction; 1–2 few-shot exchanges beat rules for format; and the
highest-leverage line for this app: voice output rules ("responses are
spoken by TTS: conversational prose, no markdown, no bullet lists, no
URLs read aloud, 2–4 sentences unless asked").

**Robustness**: 429 bodies carry `google.rpc.RetryInfo.retryDelay` in
`error.details` — exponential backoff with jitter, honor retryDelay,
and do NOT retry daily-quota (RPD) exhaustion; surface "quota resets
midnight PT" instead. `streamGenerateContent?alt=sse` works on the free
tier and is the right transport for the voice loop (Live API exists but
free quotas are tight, and we keep ASR/TTS local anyway). Structured
output (`responseSchema`) is free-tier and, on Gemini 3, combinable with
tools — the right replacement for our regex citation extraction.

**Testing (proportionate)**: fake-model unit tests for the tool loop
(iteration cap, parallel calls, error feedback); a hand-run golden set of
10–20 prompts asserting tool-call *behavior*; full request/response
transcript logging in debug builds (the future eval corpus). No eval
frameworks, no LLM-as-judge.

**Security**: the API key is user-entered and stored on-device — fine for
a personal app. Never distribute an APK with a baked-in key (Firebase AI
Logic + App Check is the path if this ever ships).

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
   mic foreground service, punctuation model. Known output quirks (observed
   on device 2026-09-21): ALL-CAPS, no punctuation, letter-split acronyms
   ("O K") — cosmetic for the LLM; sherpa-onnx's online punctuation/casing
   model fixes display. **Accuracy comparator (Drew's suggestion)**: the
   same AAR ships an OfflineRecognizer that runs Whisper tiny/base and
   Moonshine. **Status 2026-09-21: done** — Whisper tiny.en int8 second
   pass, A/B shown in the Ask card. Verdict: whisper is much more
   accurate → the ask now uses whisper's text (two-pass: zipformer =
   live partials + fallback). The second-pass slot is model-agnostic —
   swapping decoders is a config block + asset swap. Candidates if
   tiny.en isn't enough: **Moonshine tiny/base** (27–61M, MIT, beats
   whisper tiny/base — the right size-class comparator), whisper
   base/small.en, **Parakeet-TDT 0.6B** (best open accuracy but ~600 MB
   int8 and batch-only in sherpa-onnx — a "docked flagship" option, not
   an everyday default).
4. ~~Conversation history + search grounding~~ — **done 2026-09-21**:
   multi-turn history (last 10 turns, "New chat" resets), google_search
   tool on by default (Settings toggle), grounding citations rendered as
   "Web sources" under the reply.
5. ~~Agent hardening pass~~ — **done 2026-09-21** (from §3.7): temperature
   → default 1.0, `thinkingLevel: low`, RetryInfo-aware 429 backoff with
   daily-quota detection, Files API grounding (upload once / `file_uri`
   per turn, prefs-cached 47 h, inline fallback + 4xx invalidation),
   sectioned voice-aware system prompt, structured-output citations
   (JSON schema, plain-text 400 fallback), fixed Part-union violation
   (text and inlineData were sent in ONE part), debug transcript JSONL
   under filesDir/transcripts/. ⚠️ Live verification of the
   schema+search combo pending — we exhausted the free quota testing on
   2026-09-21; first real ask next session confirms it (fallbacks in
   place either way).
6. ~~Fetch-document tool~~ — **done 2026-09-21**: real function-calling
   loop (`AgentTools`: `download_document` + `list_library`; max 5
   rounds; model parts echoed verbatim with thoughtSignature/id; tool
   errors returned to the model as `{error}`). PDFs land via
   `DocumentStore.addDownloadedPdf` (figures + text + persistence, 30 MB
   cap); HTML becomes a text doc. Combined with google_search via
   `includeServerSideToolInvocations`. ⚠️ Live-verification pending same
   as #5 (quota); the loop mechanics are unit-tested.
7. ~~Voice loop v2~~ — **shipped 2026-09-21** (commit 5467d49): SSE
   streaming (`generateTextStreamed`) → StreamingAnswerExtractor (answer
   field out of structured JSON as it streams) → SentenceChunker →
   `enqueueSay` progressive TTS; Silero-VAD barge-in
   (`BargeInGuard`, VOICE_COMMUNICATION + AEC, "Voice interrupt"
   setting). **Barge-in device-verified with live room audio** (it
   interrupted Piper playback on real speech and auto-asked). SSE wire
   format still needs one live call (quota). Interactions API migration
   deliberately deferred.
8. ~~"Read with me" mode~~ — **shipped 2026-09-21** (commit 3e31fcf):
   per-document guided reading (pure `Sections` splitter), auto-advance,
   speak-to-interrupt with local intents ("continue", "next section",
   "stop reading"), questions carry the reading cursor as context and
   reading resumes after the answer. Not yet exercised on device.
9. ~~Neural TTS~~ — **shipped 2026-09-21** (commit 72a72e1): Piper
   en_US-amy-medium via sherpa-onnx OfflineTts, default voice, automatic
   session-fallback to system TTS on failure; device-verified speaking.
10. Later: Groq fallback provider, real figure extraction (vector-native),
    richer AA surface, Koog if the agent outgrows hand-rolled.

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
- **Hand-rolled agent loop, raw REST** — no LangChain-class deps in the APK;
  Koog is the graduation path, Google's Kotlin Gen AI SDK the boilerplate
  option. (2026-09-21)
- **Stay on generateContent for now; migrate to the Interactions API with the
  voice-loop increment** — server-side state is the right fit, but free-tier
  chains expire after 1 day, so client-side history remains the fallback.
  (2026-09-21)
- **Gemini 3 config**: temperature at default 1.0 (docs warn lower degrades),
  thinkingLevel low, generous maxOutputTokens with length constrained via
  prompt. (2026-09-21)
- **Google Search grounding is treated as best-effort, never required.**
  Empirically (2026-09-21) this free-tier key has zero search-grounding
  quota: any request with the `google_search` tool attached 429s instantly
  (bare body — no RetryInfo, no quota name) while the identical request
  without it succeeds, all day long, so it is not the daily reset. The agent
  now drops the search tool on such a 429 and retries once, remembering for
  the rest of the process; `download_document` still works from the model's
  parametric knowledge of canonical URLs (arxiv.org/pdf/<id>). Bare 429s are
  also no longer retried same-shape (only RetryInfo-bearing ones are).
  Usage/limits dashboard: https://ai.dev/rate-limit. (2026-09-21)
- **Own search tools instead of Google grounding**: `search_papers` (arXiv
  primary + Semantic Scholar best-effort, both keyless — S2's anonymous pool
  429s routinely and that is treated as normal) and `search_web` (Tavily,
  free tier ~1k req/month, key in Settings). Tools the current config cannot
  serve are not declared to the model. Domain APIs beat general search for
  the paper workflow: structured results with direct pdf_url feed straight
  into download_document. (2026-09-21)
- **Prompt-cache alignment is a layout rule, not an optimization pass.**
  A study session is many turns over the same documents, so the request
  prefix — system prompt, grounding parts, history — must stay byte-stable
  turn over turn for Gemini's implicit cache (min ~4096 tokens; grounded
  requests easily qualify). Concretely: per-turn context (read-with-me
  cursor, grounding toggle) rides as a late user-role context message just
  before the question, NEVER in the system instruction; tool declarations
  are snapshotted per ask; history trims in blocks of 4 turns instead of 1
  so the prefix survives between trims; tool-loop rounds extend the prior
  request, which is itself cache-friendly. Verification: usage now shows
  `cached=N` (usageMetadata.cachedContentTokenCount) per turn. (2026-09-21)
- **Tool-loop hardening is contract-driven** (audit 2026-09-21, commit
  6264d70): functionResponse parts built with a real JSON library, streamed
  parts coalesced before the verbatim echo, per-round streaming reset,
  iteration cap ends with a forced answer (never an empty reply), errors
  reach the user as one short spoken sentence plus an error line — never as
  fake model turns; model-supplied URLs are validated (https, public hosts,
  text/pdf only) before fetching, including after redirects. (2026-09-21)
