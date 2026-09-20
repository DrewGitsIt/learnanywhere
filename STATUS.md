# DriveGuide 🚗

## Build status
- ✅ JVM tests pass (`:app:testDebugUnitTest`, 6/6)
- ✅ Debug APK builds (`:app:assembleDebug`)
- ✅ Rename DriveGuide → LearnAnywhere complete (see "Renamed" note in README)

## Device test (needs a real Android 14 phone + USB)
1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Open the app → Settings → paste a free Gemini key (aistudio.google.com)
3. TAP **Test connection**
   - First attempt returned HTTP 4xx (see "Known live bug" below).
4. Add a PDF / URL / pasted text → **Ask the agent** → check reply + citation
5. Tap a figure → **Caption** → check Gemini vision caption
6. **▶ Play selected** → TTS speaks the doc; verify pause/next/prev/speed
7. Kill the app, reopen → library should persist (Room)
8. (Android Auto, in-car) — see `car/LearnStudyMediaService.kt`

## Known live bug (to debug after the rename)
Tapping **Test connection** with a valid free key throws
`GeminiError: -` (message is a single dash). That comes from
`Gemini.kt:generateText` → `summarizeError(resp.body, fallback)`.
The actual Gemini HTTP response body is being swallowed into a one-char string.

Likely causes (in order of probability):
- **`x-goog-api-key` header not accepted** by the model's `:generateContent`
  endpoint → API returns a 4xx, whose body we parse as a single `-`.
  Fix: try the `api-key` query parameter form, or the
  `Authorization: Bearer <key>` header (free-tier sometimes prefers
  `x-goog-api-key`, but some regions / key-types need the Bearer form).
- **Model id mismatch**: `DEFAULT_MODEL = "gemini-2.5-flash"`.
  Verify the exact id available on the free tier for this key; it may be
  `gemini-2.5-flash-lite`, `gemini-2.0-flash-preview`, or similar.
- **`generationConfig.temperature` rounding error** in the body we send
  (we currently pass a `Float`, so `0.4f` serializes as `0.4` — fine, but
  double-check the body via `GeminiBodyBuilder.generateContent`).

Debug steps:
```bash
# From the machine where you built; phone has to be connected via USB.
adb logcat -c
# On phone: tap Test connection
adb logcat -s Gemini:I | tail -80
```
We had a temporary `Log.i("DriveGuideGemini", ...)` added around the
`newCall(req).execute()` block — it's now removed for the push.
Re-add temporarily to see the exact HTTP code + body.

## Next incremental work (after the bug is fixed)
1. **Figure captions in the agent reply** — currently `DriveGuideAgent.ask`
   never reads `doc.figures` captions into the grounding text. The agent can
   still name a figure but can't describe it. Pass each figure's caption
   (or the first 200 chars of the doc text) into the system instruction so
   the model can say "Figure 2 shows…" without a separate vision call.
2. **In-car figures** — `LearnStudyMediaService` only has "Figures (spoken)"
   (`driveguide://figures`, now `learnanywhere://figures`). Add a
   `MediaBrowserCompat.MediaItem` per figure with `FLAG_PLAYABLE` so the
   head unit can display it (AA's media card can render images on API 30+).
3. **PDF text quality** — `PdfFigureExtractor` renders pages but
   `DocumentStore.extractPdfText(bytes)` is a naive heuristic. Consider
   android's `PdfRenderer` for the *text* layer too (not just pages).
4. **Android Auto CALS** — the current path uses MediaBrowserService
   (works on AA 10+/13+). If you want the full "app card" treatment with
   per-document cards + a custom UI, you'll need the `com.google.android.car.cals`
   SDK (API 30+, and a `CALS`-compatible AA build). That's a separate,
   larger dependency. Worth only if MediaBrowserService is insufficient.
5. **Persistence hardening** — `AppDb` is fine for the MVP, but consider
   `fallbackToDestructiveMigration` → `Migrations` as you evolve the schema.

## File map (post-rename)
```
app/src/main/java/com/learnanywhere/
├── LearnAnywhereApp.kt       # Application; wires store/agent/db/ui
├── MainActivity.kt           # Single Compose host activity
├── agent/
│   ├── Gemini.kt             # OkHttp :generateContent client
│   ├── LearnAnywhereAgent.kt # orchestration: grounding+prompt+caption
│   └── OkHttpClientFactory.kt
├── app/db/
│   ├── LearnAnywhereDb.kt    # @Entity DocumentRow + @Dao + @Database
│   ├── AppDatabase.kt        # facade + Document↔Row mapping
│   └── AppPdfStore.kt        # on-disk PDF + figure byte store
├── audio/
│   └── AudiobookPlayer.kt    # TTS player + StateFlow
├── car/
│   └── LearnStudyMediaService.kt   # Android Auto MediaBrowserService
├── core/
│   ├── Figures.kt            # imageRatioScore + captionPrompt (pure)
│   ├── GeminiBodyBuilder.kt  # pure JSON body builder
│   ├── Http.kt               # URL fetcher (OkHttp delegate)
│   └── (UrlText stripHtml is in GeminiBodyBuilder.kt at the bottom)
├── data/
│   ├── Document.kt           # Document + Figure data classes
│   ├── DocumentStore.kt      # in-memory view + callbacks
│   └── PdfFigureExtractor.kt # PdfRenderer page→Bitmap figure detect
└── ui/
    ├── LearnAnywhereScreen.kt   # Compose UI
    └── UiController.kt          # UI state hub
app/src/test/java/com/learnanywhere/
└── LearnAnywherePureTest.kt
```
