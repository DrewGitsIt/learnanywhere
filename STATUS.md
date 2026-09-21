# LearnAnywhere — status

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
