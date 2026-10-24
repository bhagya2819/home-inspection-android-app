# Code review: Griha 0.7.0 (`e251ab4..HEAD`, branch `claude/camera-preview-report-zoom-fbefa9`)

Review only; nothing modified. `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (Gradle stopped afterwards).
Go tests were NOT run (not allowed on this Mac), so server findings come from reading the code.
DeepSeek parameters were checked against the official API reference: `deepseek-flash`, `thinking:{type}`,
`reasoning_effort: low`, `user_id` (letters, digits, `-`, `_`, ≤512, a UUID qualifies) and `response_format: json_object`
(prompt must say JSON; it does) are all valid. Temperature is ignored in thinking mode (only sent when thinking is off, so fine).

No Critical findings. Nothing found that loses photos or breaks the offline invariants. The worst problems are
about money control, losing typed text, and stale AI results.

---

## Findings (most severe first)

### 1. HIGH: the daily AI limit is check-then-act; concurrent requests overshoot it
`server/internal/api/ai.go:~255-275` (check) and `~345` (usage insert, after the DeepSeek call).
`runAI` reads `sum(requests)` and compares to the limit, then makes a call that takes 3-90 s, and only then increments usage.
Any number of requests that arrive before the first one finishes all pass. The stated purpose of the limit (CLAUDE.md,
AiScreen text: "caps the bill if a phone is lost") is precisely the adversarial case: a stolen/shared session token can fire
hundreds of parallel `POST /v1/ai/review` (6000 max tokens, thinking) before any row is written.
The phone UI itself prevents this (`aiBusy`, `Review.Running`), so only a scripted client hits it.
Confidence: high (read the code path; no lock, no atomic reserve).
Fix: reserve first. One statement: `INSERT ... ON CONFLICT DO UPDATE SET requests = requests+1 WHERE (sum over kinds) < limit`
(or a `pg_advisory_xact_lock(hash(user_id))` around check+reserve), then call DeepSeek, then record tokens/failure
on the already reserved row. Optionally a per-user in-flight cap of 2.

### 2. MEDIUM: usage is lost if the phone disconnects (and failures cost quota)
`ai.go` usage `s.db.Exec(ctx, ...)` uses the *request* context.
Scenario: the phone drops (tunnel, airplane mode, app killed). Go cancels `r.Context()`, DeepSeek call aborts
(possibly already billed for tokens), and the usage insert fails on the cancelled context, so it is never counted and the
limit is bypassed, only logged as "ai usage". Conversely, every failed call (DeepSeek down, 402 out of credit,
bad JSON) *does* consume one of the user's daily requests (`requests` is incremented regardless of `failed`),
so 100 failures during an outage lock an inspector out for the day.
Confidence: high.
Fix: insert usage with `context.WithoutCancel(ctx)`; do not count requests that failed before DeepSeek accepted them
(401/402/429/5xx/network), or at least count them in `failures` only and check `requests - failures` against the limit.

### 3. MEDIUM: accepting a suggestion silently overwrites text typed after the request started
`FindingScreen.kt` `askAi()` / `accept()` (~l.150-200).
`askAi` snapshots `draft`, the request takes 3-60 s, and the suggestion is compared with the *new* draft only to decide whether
to show it. If the inspector keeps typing in "Detailed observation" while waiting, or edits the field while a card is
visible, tapping ✓ replaces the whole field with the AI text computed from the old input. No undo.
Also `Accept all` does this to up to 6 fields at once. Same in `SummaryDialog` (`text = s`).
Confidence: high.
Fix: store the value each suggestion was computed from; on accept, if the field has changed since, drop the card
(or show "text changed, ask again"). Cheap option: disable the text fields (or show the cards read-only) while `aiBusy`,
and remove a card when its field is edited.

### 4. MEDIUM: summary dialog loses everything on a stray tap or rotation
`ReportScreen.kt:345,430-437,656`. `editSummary` is plain `remember`, `AlertDialog` dismisses on outside tap, and the typed text
is in `rememberSaveable` *inside* the conditionally composed dialog, so closing the dialog discards it. A 150-word summary
(or a paid-for AI draft, quota already spent) is gone after one tap outside the box. Activity recreation (rotation, dark-mode
switch from Profile, process death) also closes the dialog (state not saveable).
Confidence: high for outside-tap; medium for recreation (manifest has no `configChanges`/orientation lock; the new
ThemeSettings switch recreates nothing as far as seen, but system dark-mode change does).
Fix: `properties = DialogProperties(dismissOnClickOutside = false)`, make `editSummary` `rememberSaveable`,
and keep the draft text in the ViewModel.

### 5. MEDIUM: AI check results go stale and stay on screen
`ReportScreen.kt` `Review.Done` is kept in the ViewModel forever. After the inspector opens a flagged finding,
fixes it and comes back (or edits anything, or finalizes later), the bar still says "AI check: 3 to look at" and the dialog lists
the old issues; nothing invalidates it on `inspection.updatedAt` change. Issues for findings since deleted show "Untitled finding"
and are not clickable. Confidence: high.
Fix: store `at`/`updatedAt` with the result; reset to `Idle` (or label "out of date") when `updatedAt` changes.

### 6. MEDIUM: removing the site note from the PDF silently blanks old and note-only findings
`ReportRepository.kt:~251-262`. Observation is now `detailedObservation` only and the title falls back to category.
Any finding where the inspector wrote only the site note (every 0.5.x inspection still in progress, and any inspector who
doesn't use AI) prints a heading with no text, and `finalize` does not warn. This matches the 2026-10-21 decision, but nothing
protects the pilot users from sending an empty-looking report. Verified no other path prints `roughNote`
(grep in `core/report/` is empty; the AI review/summary use it only for the server call).
Confidence: high on behaviour; severity depends on your intent.
Fix: block or warn on Finalize when any finding has no observation ("3 findings have no report text"), and list them.

### 7. MEDIUM: starting a capture and tapping a thumbnail immediately loses that photo
`CameraScreen.kt` thumbnail click -> navigate -> composition leaves -> `bindToCamera`'s `finally { provider.unbindAll() }`.
The code comment says "saves in flight continue", which is true only after `onImageSaved` (commit runs in `appScope`).
A `takePicture` still in progress (MAXIMIZE_QUALITY at full sensor resolution takes ~1 s) when the camera is unbound
fails with `onError` ("Photo NOT saved. Please retake it."). Not silent and no data corruption (temp file discarded),
but a photo the inspector believed taken is gone. Confidence: medium-high (CameraX closes pending requests on unbind;
not exercised on a device).
Fix: ignore thumbnail taps while `ui.saving > 0` (or wait for it before navigating).

### 8. LOW-MEDIUM: pull/push round-trip hazards with `summary` (all handled except size)
- Insert path: `summary` absent -> NULL, ok. Update path: `keepIfAbsent` correctly skips the column when the key is absent;
  explicit JSON `null` clears (matches API.md). Conflict path returns the full row (summary included, via `selectList`);
  force path goes through the same UPDATE. Exports use `selectList`, so `data_json` includes it. Old apps ignore the extra
  key (hand-written codec). Checked fine.
- `convert()` limits text to 20,000 bytes. The summary field has no length limit on the phone. A pasted >20 KB summary makes the
  server REJECT the whole INSPECTION push forever (the sync item fails, retried, shows in Admin problems), blocking that
  inspection's other edits. Fix: `maxLength` ~3000 chars in the dialog.

### 9. LOW: AI error handling gaps on the phone
- `AiRepository.call` maps every `IOException` to "No internet connection". A read timeout (server took >120 s or the
  review is slow) shows the same message, so the inspector thinks they are offline and retries (spending quota).
  Distinguish `SocketTimeoutException`.
- Only `AiException` is caught in `askAi`, `runReview`, and the summary dialog. A 200 with a non-JSON body
  (`Json.parseToJsonElement` -> `SerializationException`) crashes the app via `viewModelScope`. Unlikely (Caddy/Go always
  send JSON) but cheap to cover with `catch (e: Exception)`.
- 413 `TOO_LARGE` (review body > 1 MiB, ~hundreds of long findings) surfaces as "AI suggestions failed (request body too large)".
- Server `aiMaxFindings = 300`: `break` leaves the outer loop running; findings beyond 300 are silently never summarised/reviewed.
- `ApiClient.request`: Android's HttpURLConnection/OkHttp may silently retry a POST after a connection reset, which can
  double-spend one AI request. Minor.

### 10. LOW: dropped requests still bill; no cancel
`viewModelScope` cancellation on leaving the finding screen does not abort the blocking `HttpURLConnection` (it only stops
when the read returns). The user leaves, the server still calls DeepSeek, quota is consumed, the result is discarded.
Acceptable; note for the pilot.

### 11. LOW: `findingToOpenOnDone` after photos were moved away
Camera -> thumbnail -> viewer -> "Move" the only photo elsewhere -> camera `Done`: `savedThisSession > 0`, so it navigates to
`FindingRoute` for a finding with 0 photos and no text. Screen works (finding row exists), but is an empty finding that
`removeEmptyFindings` tombstones at next start. Harmless; note only.

### 12. LOW: zoomable report pages
Math checked and correct (focus-preserving `zoomTo`, symmetric clamp, edge hand-off via `NestedScrollConnection`, single-finger-after-pinch).
Remaining issues:
- At scale > 1.3 each visible page re-renders at 2400 px wide: ~33 MB per page (the comment says ~25 MB) plus the old 1440 px bitmap held
  until the new one lands. `Bitmap.createBitmap` OutOfMemoryError in `PdfPages.render` is uncaught inside `produceState`, so a
  low-RAM phone would crash instead of falling back. Catch `OutOfMemoryError` and keep the old bitmap.
- `produceState(pages, index, widthPx)` resets to `null` when `pages` changes (any edit re-renders the draft), so pages blink white
  during a refresh.
- `offset` is not re-clamped when the box size changes (rotation); self-corrects on the next gesture.

### 13. LOW / NIT list
- `ai.go` `aiRefPattern` / `refs`: model refs like `f3` (lowercase) are dropped; labels are built with `%q`, so a title containing a
  quote shows a visible backslash (`\"`). Titles are not stripped of newlines before `[F#]` headers, so site-note text can forge
  `[F9]` blocks (prompt injection). Impact is limited to suggestions: every output field is validated/clipped and applied only on ✓.
- Prompt injection via site notes is possible (no delimiters) but harmless as above; the JSON is the only thing the model controls
  and `finding_id` comes from the server's ref map.
- `ai.go` DeepSeek transport errors are not logged (only returned as AI_UNREACHABLE): hard to diagnose in production.
- `http.Client{Timeout: 100s}` is redundant with the 90 s context (90 wins). Chain is consistent: 90 s server < 100 s client < 120 s phone read;
  Caddy has no response timeout; `http.Server` has no write timeout. But `server/API.md` says the review "allow ~2 min" while the server
  cuts it at 90 s and the dialog says "up to a minute". With 100+ findings and thinking, 90 s may genuinely not be enough (AI_TIMEOUT, quota spent).
- `sync.go` struct field `keepIfAbsent` breaks gofmt alignment of the neighbouring comment/field (run `gofmt`).
- `MigrationTest.migrate3To4KeepsData` uses Kotlin `assert(it.isNull(4))`; assertions are disabled on Android, so that line checks nothing. Use `assertTrue`.
  (The test has also never been run: needs a debug build on the phone, per CLAUDE.md.)
- `AiScreen.kt`: `LimitDialog` is composed inside a `LazyColumn` item (disappears if the item is scrolled/reloaded while `dialog` stays set).
  "about Rs. 0.05 per suggestion" is an unverified price claim shown to the admin.
- `AiRepository.suggestFinding` loads all photos of the inspection to filter by finding; use a per-finding query.
- Photo captions are sent to DeepSeek and are free text; the "never client name" promise (AiScreen, API.md, CLAUDE.md) is only true if
  inspectors never type names in captions/notes. Site notes too. Worth one line in inspector docs.
- `AdminHubViewModel` is not dead: `RetentionPolicyScreen` still uses it (the name is now misleading). `AdminLinks.ai` is wired. No leftovers from the merge found
  in FindingScreen/AdminScreens/NavHost; the finding form kept the redesign with the AI bar and cards inside it.
- Audit `AI_KEY_SET` and `ai_key.last4` record the last 4 key characters (fine, intended).

---

## Checked and found fine
- **Key sealing**: AES-256-GCM, random 12-byte nonce per seal, AAD constant, key length validated at startup (64 hex) and again per use; wrong
  `GRIHA_SECRET_KEY` fails closed with a generic 500 (message not returned to phones). `INSERT ... ON CONFLICT (id)` single-row table.
- **Key leakage**: never returned (only `last4`), not in logs (`logRequests` logs method/path/status only), error bodies are fixed strings, DeepSeek error
  bodies logged are DeepSeek's (≤500 chars), not the key. Admin endpoints use `s.admin(...)`; AI endpoints `s.auth(...)`.
- **Key check**: `GET /models` before storing; 401/403 -> 400; transport/5xx -> 502; no write on failure.
- **India day boundary**: `(now() AT TIME ZONE 'Asia/Kolkata')::date` used consistently for check, insert and the monthly report.
- **JSON decoding**: Go struct matching is case-insensitive so `title`/`Title` both work; missing fields are empty; severity/category validated against the lists;
  all text ASCII-normalised and clipped; empty answers produce "nothing to change" on the phone.
- **Review ref mapping** (`F\d+`): `[F3]`, `F3,` and `F12` vs `F1` are handled correctly (word boundaries + greedy digits); unknown refs left as is; issues capped at 25;
  `kind` whitelisted; ids come only from the server-side map.
- **Input limits**: finding 64 KiB, inspection 1 MiB bodies via `readJSON` (MaxBytesReader, trailing-data check); per-field clipping.
- **Sync `keepIfAbsent`**: see #8; old-app pushes cannot erase a summary; explicit null can.
- **DB v4**: `MIGRATION_3_4` is a plain additive `ALTER TABLE ... ADD COLUMN summary TEXT`, registered, schema `4.json` present,
  `updateSummary` runs in one transaction with the entity update, `SyncOutboxDao.enqueue` and `reportContentChanged` (touch + reopen FINALIZED -> IN_PROGRESS with its own enqueue);
  no-op when unchanged. Invariants (tombstones, outbox-in-transaction, no destructive migration) hold. `SyncCodec` writes `summary` (null as JSON null) and reads it back.
- **AutoSaver / three-way merge** with `accept`/`acceptAll`: each accept builds on the already-updated `draft`, the keyed saver keeps the last full state.
- **`ai_enabled` caching**: persisted in prefs, refreshed after each successful sync and on admin changes; `AI_OFF` resets it. Stale `true` only costs one failed tap.
- **R8**: `AiInfo`/`AiUserUsage` follow the same `@Serializable` + `Json{SnakeCase}` pattern as the existing admin models that already work in release 0.5.x
  (library consumer rules keep serializers). Not verified by running a minified build.
- **Report**: summary paragraph goes through `wrap` (handles `\n`, WinAnsi normalisation) and `ensure()` pagination per line; unit test covers it.
- **Docs**: `server/API.md` matches the endpoints and error codes; CLAUDE.md and release notes match the code, except the "~2 min" review timeout noted above.
  Version 0.7.0 / code 6 correct in `app/build.gradle.kts`.

## Not checked
Real-device behaviour, Go tests, the release build/APK size, `MigrationTest` execution, DeepSeek responses with the real `reasoning_effort: low` path (only fake servers in `ai_test.go`).

## Top issues
1. Daily AI limit can be overshot by concurrent requests (and disconnects skip accounting), so it does not truly cap spend.
2. AI suggestions and the summary dialog can overwrite or lose typed text (stale suggestions, outside-tap dismiss).
3. Stale AI-check results stay visible after fixes.
4. Reports now print empty findings for note-only inspections with no warning at finalize.
5. Tapping a camera thumbnail mid-capture can lose that photo (shown as an error).
