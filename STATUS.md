# LearnAnywhere — status

## Current: 3e50502 installed and live-verified (2026-09-21 evening)
- **Crash fixed**: Drew's mid-reply crash was a native SIGSEGV — BargeInGuard's
  loop gated on the shared job field, so rapid stop()/start() (streaming TTS
  flaps playback per sentence) let two loops hit the same sherpa-onnx Vad
  concurrently. Loop now obeys its own coroutine context; all native VAD calls
  serialized behind vadLock. Two spoken multi-turn replies verified, no crash.
- **Prompt-cache alignment verified live**: usage(stream) transcript lines show
  prompt=17093 cached=14726 (86%) — including across an app restart, since
  Gemini's implicit cache is server-side and the prefix is now byte-stable.
- Audit-hardening build (6264d70) regression-checked in the same session:
  streamed tool turns, citations (doc + figure chips), spoken replies.

## Own search tools 2026-09-21 (replaces dead google_search grounding)
- `search_papers` (arXiv + Semantic Scholar, keyless) — live-verified: "Find
  the BERT paper" ran search_papers → list_library → download_document and
  spoke the confirmation.
- `search_web` (Tavily) — declared only when a key is set (Settings field;
  free at tavily.com) AND web search is on. Built to the documented API; not
  yet exercised live (no key).
- Tool-loop audit (fresh-eyes Opus subagent) → 12 findings fixed in 6264d70:
  per-round streaming reset, iteration-cap answer forcing, org.json-built
  functionResponses, download URL/content hardening, honest error surfacing
  (short spoken sentence + error line, never TTS-read HTTP dumps), streamed
  rawParts coalescing, concurrent tool execution + "Searching…" cues, 180s
  turn timeout + barge-in cancellation, history mutex, VALIDATED toolConfig.

## Live verification 2026-09-21 (device, real quota)
- ✅ **Full agent flow verified end-to-end on the OnePlus 9**: text ask
  "Find the paper Attention Is All You Need and add it to my library" →
  SSE-streamed structured reply, two tool-loop iterations
  (thoughtSignature/id round-trip), `download_document` fetched the 2.2 MB
  arXiv PDF, library row created with 39.5k chars of extracted text
  (15 pages), spoken confirmation. A mid-loop 503 ("high demand") was
  retried automatically.
- ✅ **Search-grounding 429 fallback verified live**: the search-enabled
  request 429'd once (bare body), the agent immediately retried without
  `google_search` and the session remembers the trip. See DESIGN.md §5.
- gemini-3.6-flash was intermittently 503 "high demand" this afternoon —
  transient Google-side congestion, unrelated to quota.

## Build status
- ✅ JVM tests pass (`:app:testDebugUnitTest`, 6/6) — last verified before the 2026-09-20 cleanup pass
- ✅ Debug APK builds (`:app:assembleDebug`) — same caveat
- ⚠️ The 2026-09-20 cleanup pass was made on a machine **without an Android SDK/JDK** —
  the changes below are conservative but have NOT been compile-checked. Run
  `./gradlew :app:testDebugUnitTest :app:assembleDebug` first.

## Fixed in the 2026-09-20 cleanup pass (verify on device)
1. **"GeminiError: -" bug** — `summarizeError()` in `Gemini.kt` appended `"-"`
   when the error body didn't match the expected envelope, so the fallback
   (`HTTP <code>`) never fired and the real response body was discarded. It now
   returns `HTTP <code>: <first 200 chars of body>` when the standard error
   shape isn't found, so the next Test-connection failure will say *why*.
   (Also fixed: `extractFirstJsonString` treated `indexOf() == -1` as a hit.)
2. **Silently swallowed add failures** — `DocumentStore.addPdf/addUrl` return
   `Result`, but `UiController` never unwrapped them, so a failed PDF read or a
   bad URL just did nothing. Now `.getOrThrow()` inside the try, surfaced via
   `error`.
3. **Test connection had no success feedback** — added `UiController.info` +
   status line in the Settings card ("Testing…" / "Connected ✔ (model)").
4. **Android Auto was never discoverable** — the manifest lacked the required
   `com.google.android.gms.car.application` meta-data. Added it plus
   `res/xml/automotive_app_desc.xml` (`<uses name="media"/>`). Without this AA
   ignores the app entirely, regardless of the MediaBrowserService.
5. **Audiobook only read the first ~700 chars of each document** —
   `AudiobookPlayer` now splits the full text into sentence-aligned ≤600-char
   chunks and enqueues them with `QUEUE_ADD`; doc advance happens on the *last*
   chunk's `onDone`. Pause/resume resumes from the current chunk.
6. **First Play/say tap raced TTS init** — engine init is async; requests that
   arrive before `onInit` are now parked and replayed when the engine is ready.
7. **The live 4xx root cause (2026-09-21, diagnosed against the real key)**:
   `gemini-2.5-flash` returns a hard 404 for new API keys — "no longer
   available to new users… use models/gemini-3.6-flash" — even though
   ListModels still lists it. Default model is now `gemini-3.6-flash`; the
   Settings chips offer 3.6 Flash / 3.8 Flash / Flash-Lite (all verified 200
   with this key). Related: Gemini 3.x thinking tokens count against
   `maxOutputTokens`, so the old tiny budgets (ping=8, caption=48) returned
   empty text with `finishReason=MAX_TOKENS`; budgets raised (ping 256,
   caption 512, default 2048).

## Device test (needs a real Android 14 phone + USB)
1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Open the app → Settings → paste a free Gemini key (aistudio.google.com)
3. Tap **Test connection** → expect "Connected ✔" or a *readable* error
   (the old single-dash error is fixed; if it still 4xx's, the message now
   contains Google's actual error text — likely model id or key restriction).
4. Add a PDF / URL / pasted text → **Ask the agent** → check reply + citation
5. Tap a figure → **Caption** → check Gemini vision caption
6. **▶ Play selected** → TTS speaks the *whole* doc; verify pause/resume
   continues mid-doc, next/prev/speed work
7. Kill the app, reopen → library should persist (Room)
8. (Android Auto) — app should now appear in AA's media apps list
9. **Voice input** (2026-09-21): tap **🎤 Speak** in Ask → grant mic →
   first tap loads the model (a few seconds, "⏳ Loading…") → speak a
   question → partials appear live in the field → stopping talking for
   ~1.2 s auto-asks (or tap ◼ Stop to finish early). All recognition is
   on-device (sherpa-onnx zipformer int8). After cloning fresh, run
   `scripts/fetch_speech_assets.sh` before building.

## Known limitations (not bugs, but the honest state)
- ~~PDF audiobook is silent~~ — **fixed 2026-09-21**: pdfbox-android extracts
  the text layer at add-time (`PdfText.extract`, re-flowed for TTS), and a
  cold-start backfill upgrades previously-added PDFs. Remaining gap: scanned
  (image-only) PDFs have no text layer and still won't play; Gemini Q&A on
  them works regardless.
- `Gemini.kt` hand-parses JSON with regex/scanning; fine for the happy path,
  brittle for e.g. `"text"` fields containing escaped quotes in unusual spots.
  Consider org.json (available on Android; keep the pure JVM test in mind).
- README's free-tier numbers drift (see DESIGN.md — checked 2026-09-20:
  inline request cap is 20 MB total, 50 MB/1,000 pages is the *Files API*;
  free-tier data is used for training outside EEA/UK/CH).
- Room ships `fallbackToDestructiveMigration()` + `exportSchema = false`.

## Next work — see DESIGN.md
The product direction (fully-vocal interface, local ASR, read-along visual
mirror) and the research behind it live in `DESIGN.md`.
