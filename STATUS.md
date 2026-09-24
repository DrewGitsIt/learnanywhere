# LearnAnywhere — status

## Current: DESIGN §7 built and installed; live-verified except answer-depth eval (2026-09-22 morning)

**Model failover chain (DESIGN §8, 2026-09-24) — built + tested, NOT yet
live-verified on device.** Gemini calls now walk a chain (Settings chip →
3.8-flash → 3.6-flash → flash-lite, deduped) instead of surfacing an error:
a 503 after the in-model retries benches that model for 5 minutes, a
daily-quota 429 benches it until the next midnight Pacific (RPD is
per-model). Bare 429s, 4xx and transport failures behave exactly as before;
Settings "Test connection" still tests the chip only; a stream that has
already spoken never fails over. Pure policy in `agent/ModelFailover.kt`,
chain loop in `Gemini.kt`, one shared instance on `LearnAnywhereApp`.
Failovers show up in the debug transcript as `kind:"failover"` — that is what
to look for live; the §7 leftovers (answer-depth eval, classifier under the
new heuristics union) are the natural thing to retry now that a 503/quota
storm no longer blocks a turn.

Second UX round (answer quality / silent boilerplate skip / voice seek), per
DESIGN.md §7, built by three delegated agents + fixes, all merged, 144 JVM
tests green, installed on-device (verify APK freshness — see gotcha below):
- **Answer quality**: teaching-persona system prompt (intuition-first, terms
  defined inline, concrete numbers from the docs, one-line takeaways,
  adaptive length replacing the 2–4-sentence cap); ask calls now use
  thinkingLevel medium + 8192-token budget; default model → gemini-3.8-flash.
  One-time prompt-cache invalidation observed as expected (cached=null).
- **Silent boilerplate skip**: sidecar skip-maps (stored text never mutated).
  LLM classifier labels regions by verbatim boundary quotes at add-time
  (warming at startup/add; failures are NOT persisted so a later start
  retries); offline heuristics are floor and ceiling. KEY FINDING:
  PdfText.extract emits ONE newline-free line, so line-anchored heuristics
  never fire on PDFs — flowed passes added (refs list via heading-glued-to-
  first-entry + citation-density walk for the END, so BERT's post-references
  appendix survives; head rights-grant sentence-bounded, gated to newline-free
  heads; ack anchored to a found refs list). Sidecar loads are unioned with
  CURRENT heuristics (improvements reach already-classified docs; also patches
  LLM quote ends clipped one punctuation char short by canonical matching).
  Live: Attention read-with-me went 34 → 27 sections, section 1 opens at the
  title, license/arXiv-stamp/ack/references silenced.
- **Voice seek** (`seek_quote` in the reply schema → TextLocate → section
  jump, pending-seek fires after the spoken confirmation): live-verified
  mid-reading — "Drop me in the part about positional encoding" → spoke
  "Jumping to the positional encoding section." → jumped section 1 → 13 of
  27, page thumb 1 → 5 (correct page). Works idle and mid-reading; misses
  degrade to a normal answer.
- **Still unverified live**: answer-depth quality on a real substantive ask,
  and the LLM classifier under the NEW heuristics union — both blocked by
  Google-side 503 "high demand" on BOTH flash models plus daily-quota
  exhaustion on 3.8-flash (RPD is per-model; testing burned 3.8's).
  Model pref is explicitly gemini-3.6-flash for now (chip in Settings).
- **Device state to restore** (phone dropped off USB first): Voice interrupt
  (barge_in) still FALSE from deterministic testing — flip in Settings or
  re-patch prefs; `svc power stayon` may still be set.
- **Gotchas learned**: (1) a `./gradlew assembleDebug | tail -1` chain masked
  a build that never produced a new APK — a stale binary got installed and
  its lucky classifier 200 persisted LLM-only ranges; always check BUILD
  SUCCESSFUL *and* the APK mtime before `adb install`. (2) barge-in still
  self-triggers off the phone's own TTS (second sighting; mic transcribed
  garbage into an ask mid-reading) — needs a VAD-threshold/AEC tuning pass.

## Previous: c87fbe4 installed; visual verification completed morning 2026-09-22
UX overhaul per DESIGN.md §6, built by three delegated agents (audio / data /
UI) with exclusive file ownership, reviewed and merged:
- **Home is pager page 0** (hero always reachable — fixes "empty state gone
  forever"); pages 1..N are sessions, swipe to resume, stable page order.
- **One `+ Add` sheet** replaces the PDF/URL/Paste triple.
- **Karaoke read-along**: every utterance publishes its full text
  (`PlaybackState.activeText`); replies and read-with-me highlight the spoken
  sentence; read-with-me sections are sentence-chained (`sayChain`), so rate
  changes land within a sentence and auto-advance rides the chain's onDone.
- **Figures**: replies carry `cited_page` (schema + Room v3 migration —
  verified on-device: user_version=3, docs intact); reply bubbles and the
  reading card show the page render, tap to enlarge.
- **Settings sectioned** (AI model / Tools / Voice & playback) with an
  idle-accessible speed control; **rate now persists** (was never written).
- Speed itself was never broken — on-device timing: 17.1 s @ 1× vs 12.8 s
  @ 1.5× (doc playback), 25.7 s @ 0.75× vs 15.9 s @ 1.5× (read-with-me).
- 89 JVM tests green. Device checks still pending (phone auto-locked):
  pager feel, karaoke sync, auto-advance-once, figures on a real ask,
  speed persistence across relaunch, Add sheet paths, IME insets.
- A throwaway "Speed test" pasted doc from the timing experiment is still in
  the library — remove via its row menu.
- Android Auto figure display (metadata art) deliberately deferred: needs a
  MediaSession + head-unit test.

## Previous: 3e50502 installed and live-verified (2026-09-21 evening)
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
