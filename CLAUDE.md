# Home Inspection — offline-first house inspection app (Android)

Client app for Home Inspection. Spec: `House_Inspection_Android_App_PRD.md` (the source of truth).
UI reference: `Home_Inspection_Clickable_Prototype_v2.html`. Brand: `home-inspection-brand-guidelines.pdf`, `home-inspection-icon.png`.

## Status
- **M1 done** — skeleton, Room schema (PRD §7, minus users/devices), crash-safe photo capture, storage limits.
- **M2 done** — new-inspection form + room checklist, room rename/reorder/remove, finding editor
  (auto-save), photo viewer (zoom, captions, move), dashboard search/filters, Draft→In progress→Ready status.
- **M3 done** — on-device PDF report (`core/report/`): report-size JPEG variants (1600 px, q80, EXIF applied,
  cache dir keyed by settings + original SHA), A4 layout, PdfRenderer preview + zoom, finalize → numbered version
  in `reports` (SHA-256), share via FileProvider (`files/reports/` only). Verified on the POCO F4 (WhatsApp).
- **M4 done** — login (online first, 7-day offline window, offline verifier), sync engine (outbox push with
  revisions/conflicts, verified photo + PDF upload, cursor pull, downloads of other phones' photos/PDFs),
  JobScheduler retries, Backup & account screen. Go server in `server/` (see `server/API.md`), live at
  `https://api.homeinspection.example`. Verified on the POCO F4: 12/12 photos byte-identical on the server.
- **M5 mostly done (v0.5.0, DB v3, server migration 002 live)** — in-app admin (`feature/admin/`: employees, inspections'
  backup status, phones + their failed sync items, audit log, retention policy, server storage). Admin export/archive
  instead of paid storage: month-sized deterministic Store zips (manifest.csv + data.json), phone downloads via
  DownloadManager to Downloads/Home Inspection, re-hashes every entry → VERIFIED → "Remove from server" deletes files of finalized
  inspections ≥ 60 days old (records stay, GET → 410). Phone retention (`core/retention/`): opt-in switch, predicate =
  finalized + synced + server checksum + in a VERIFIED export + 30 days after later of finalization/verification, live
  `/v1/photos/check`, re-checked inside the deleting transaction, audited and uploaded via `/v1/devices/report`.
  Verified on the POCO F4: v2→v3 upgrade with real data, 8 instrumented tests (migration + real deletions).
- **Release:** key created by the user (`~/.home-inspection/`, PKCS12, one password; never read it). `tools/release.sh` →
  `dist/HomeInspection-<ver>.apk`, refuses debug-signed builds. Docs for inspectors: `docs/install-guide.md`, notes in
  `docs/release-notes.md`. Bump versionCode/versionName per release.
- **0.5.1 released (2026-10-20)**: the phone now runs the release-signed build (debug app uninstalled by the user, so
  `run-as` / `tools/verify-device.sh` no longer work on it; check via the server: devices.sync_report, photos.verified_at).
  Verified release 0.5.0 → 0.5.1 update over WhatsApp with unsynced offline work. Phone tests that need `run-as` now need
  a debug build, which can't be installed over the release one — ask the user first.
- **0.7.0 built (2026-10-21, code 6, DB v4, server migration 003 live)**: AI writing help (see Decisions), report summary,
  camera thumbnails open the viewer, report pages zoom in place, site notes never printed; includes the ui-redesign
  branch (0.6.0/0.6.1). Server deployed with `HOME_INSPECTION_SECRET_KEY` in `.env`; AI stays off until the admin adds the
  DeepSeek key. Not yet verified on the phone; MigrationTest 3→4 not yet run there (needs a debug build).
- **0.7.1 (code 7)**: security audit fixes (`review/security-audit-0.7.0.md`): login rate gate, soft account lock for
  known phones, server-enforced password change, 10-char passwords, 90-day session cap, 30-min sliding export links,
  PII scrub before AI, Go 1.26 + updated pgx/x-text (govulncheck clean). Server tests: 21.
- **0.7.2 built (2026-10-21, code 8)**: developer lock (see Decisions) + the 0.7.1 security branch merged in. Server
  with migration 004 deployed (previous source in `src.prev`), unlocked. Not yet installed/lock-tested on the phone.
- **Feature 1 — attendance (branch `claude/attendance-location-emergency-fd0a0b`, DB v5, server migration 005 live)**:
  face check-in/out (`core/face/`, `core/attendance/`, `feature/attendance/`). OpenCV 4.12 (YuNet detector bundled in
  assets, compact SFace int8 recognizer downloaded from `/v1/face/model`, SHA pinned in `FaceModelStore`), head-turn
  liveness, one location fix, selfie stored like photos and deleted from the phone after server verification; server
  deletes selfies after 90 days. Dashboard duty card, Profile → My attendance / Face check-in, Admin → Attendance /
  Face set-ups. Verified on the POCO with the debug app as `TEST`: enrol → approve → check-in MATCH 0.86 / out 0.88,
  ±16 m, clock check 1.2 s; a phone photo can't pass the head turn. Not released yet (no version bump).
  Next: feature 2 (live location while on duty), then 3 (SOS) — one at a time (user).
- **Feature 2 — live location (branch `claude/live-location-023c8b`, DB v6, server migration 006 live 2026-10-21)**:
  `core/tracking/`, `feature/tracking/`, server `tracking.go`. Verified on the POCO (debug app, TEST/DEVA): consent →
  manual start, check-in start / check-out stop, turn off with reason, Location off/on (events reach the server in
  ~1 s), app update → interruption → "Tap to resume" → RESUME, reopen → RESTORE, sign-out uploads STOP before the
  session ends, admin Team list/map + day view. MIUI without Autostart drops BOOT/MY_PACKAGE_REPLACED broadcasts:
  safety net in `HomeInspectionApp` (background process start + should-share + no service → INTERRUPTED + notification).
  WebView must be MATCH_PARENT (WRAP_CONTENT collapsed the map to 0 px). Not yet verified on the phone: live mode
  (15 s / 30 s, covered by server tests only), battery drain unplugged. Not released (no version bump).
  **0.8.0 (code 9, 2026-10-21)** = features 1 + 2, merged into main for the pilot (user); SOS ships later as 0.9.0.
  **0.8.1 (code 10, 2026-10-21)**: report fixes. Duplicate versions came from finalizes queued behind the lock after the
  screen was reopened, and from stray taps on the bottom checkbox + Finalize button: `ReportRepository.finalize` returns the
  latest version if the inspection is already FINALIZED, progress is app-wide (`ReportRepository.finalizing`), Finalize asks
  in a dialog, downloads stop when cancelled. Plus the Home duty/location pills.
  Next: feature 3 (SOS).
- **Admin features — calendar, leave, comp-offs, web dashboard (branch `claude/admin-attendance-calendar-9c8dda`, DB v7,
  server migration 007 NOT yet deployed)**: server `calendar.go`, `leave.go`, `web.go` + `web/`, `docx.go`, `imageproc.go`;
  app `core/leave/`, `feature/leave/`. Server tests 36 pass (govulncheck clean); web dashboard checked against a throwaway
  preview server (seeded, SSH tunnel, removed afterwards); Word sample checked in QuickLook, not yet in Word. App builds;
  not yet on the phone; MigrationTest 6→7 not yet run there.
- **Review fixes (same branch, merged with `claude/room-order-common-photos-6db4dc` (DB v8, migration 008); server with migration 009 deployed 2026-10-21 at `30fa66b`, previous in `src.prev` + `backups/predeploy-009-*.dump`; debug build on the POCO)** — `review/admin-features-review.md`:
  calendar pairs shifts across midnight (≤ 16 h, split at midnight) and uses the server's clock estimate (`clock_skew` flag);
  `users.track_attendance` (inspectors on, admins off) + `attendance_from` (joining day) + `disabled_at` decide who/when counts
  (Admin → Employees); statuses `WORKED_ON_LEAVE`, `HALF_LEAVE` + `half_work`; leave `REVOKED` (taken back) and re-approve,
  self-decision needs another admin, 0-day requests refused, worked days + comp-off balance shown where the admin approves;
  comp-offs of the month with SHORT/CLOCK checks; holidays report `affects`; phones upload a content snapshot per finalized
  version (`files/report-content/`, `PUT /v1/reports/{id}/content`) and the Word copy of any version is built from it; web:
  render-race guard, search focus, paging, `__Host-` cookie (web users sign in once more after deploy), HSTS; leave decisions
  shown on Home/Profile; attendance + leave queues wake the sync job. Server tests pass on the server (scratch copy
  `src-calendar-fixes`), govulncheck clean; app builds, unit tests pass. Not yet tapped through on the phone.
- **0.9.0 (code 11, 2026-10-22)** = admin features + room order/common photos, all branches merged into main (single
  line of code). Server at `8ee829e` (web polish) deployed 2026-10-22, previous in `src.prev` +
  `backups/predeploy-0.9.0-*.dump`; 36+ server tests pass. `dist/HomeInspection-0.9.0.apk` built. Debug 0.9.0 on the POCO (DB 6→8 upgrade on real data, integrity ok); not yet tapped through.
  Web: monthly attendance Excel (`attendance_export.go`, own minimal .xlsx writer: Summary + Day by day sheets, built from
  `buildDays`) deployed 2026-10-22; Backup page explains that exports only hold files not in an earlier checked export.
  Next: feature 3 (SOS) → 0.10.0.
- **Open** — pilot with 2–3 inspectors. Weekly Google Drive copy **parked by the user** — don't raise it unless asked.

## Decisions already made (don't re-ask)
- Native Kotlin only; **lightest possible APK** — no DI framework (hand-wired `AppContainer`), no Retrofit
  (OkHttp + kotlinx.serialization in M4), R8 full shrink. Release APK is ~2 MB; keep it small.
- minSdk 26, target/compile 37. System fonts; brand colours in `ui/theme/Theme.kt`.
- One login for online+offline; offline valid **7 days** since last server check (stored PBKDF2 hash, Keystore).
  After an offline sign-in the password is kept in memory only, to finish the online sign-in automatically.
- No OkHttp/WorkManager: `HttpURLConnection` + platform `JobScheduler` (M4 added ~66 KB to the release APK).
- Server: Go 1.26 + pgx only (run `govulncheck` on the server with server changes), own Postgres container, `/root/apps/home-inspection` on SSH host `bondverse`. Touch nothing else
  on that box; the only shared change is the Home Inspection block in `/opt/bondverse/Caddyfile` (`server/deploy/`).
  Build/test Go **on the server** (`sh test.sh`), never on the Mac (low RAM). API is client-neutral for a future iOS app.
- Everyone sees all inspections (per-user restriction switch later, server-side).
- Admin features live inside the app.
- **AI writing help (user decision 2026-10-21, reverses the PRD §2.2 non-goal):** DeepSeek `deepseek-flash`, one key for
  everyone, entered once in Admin → AI assistant, stored AES-GCM-sealed on the server (`HOME_INSPECTION_SECRET_KEY` in the server's
  `.env`), write-only, never on phones. Phones → `/v1/ai/*` → DeepSeek; prompts live in `server/internal/api/ai.go`.
  Every suggestion is shown beside its field with ✓/✗ and applied only on ✓ (the AI can be wrong). Features: finding
  rewrite (+ severity/category), report summary (`inspections.summary`, DB v4), pre-finalize check. Online-only. Never send
  client name/phone/address. Photo-aware suggestions were considered and deferred.
- **Developer lock (user decision 2026-10-21):** the developer's leverage for unpaid licence fees, not an admin feature.
  One global switch, set only over SSH (`docker exec home-inspection-api /home-inspection-api lock "msg"` / `unlock` / `lock-status`); never
  exposed over HTTP or in the app. No warning banner (user's choice): full-screen "App locked" over everything, incl. login.
  Phone locks ONLY on 423 + `APP_LOCKED`, unlocks ONLY on `/v1/app/status` 200 `locked:false`; state persists offline.
  Deletes nothing, signs nobody out; a lock must never mark outbox items failed. Lock only when the user asks.
- **Attendance (user decisions 2026-10-21):** on-device face recognition (OpenCV YuNet + SFace, Apache/MIT; InsightFace
  and EdgeFace weights are non-commercial — rejected). Compact int8 SFace (9.9 MB, 99.32 % LFW) chosen over the full
  38.7 MB one: the link to the server from India is only ~3–4 Mbit/s. Embeddings only compare within one model; the
  server ignores enrolments of other models. Thresholds MATCH ≥ 0.45, WEAK ≥ 0.30 (`FaceMatcher`), revisit after
  real use. Admin approves each face set-up. Not checked in = reminder only, never blocks work. No working hours /
  auto check-out. Engineers see only their own attendance, admins everyone's. Selfies kept 90 days. No inspection ↔
  engineer assignment or site geofence: the admin judges locations. APK size is not a concern for these features.
  Admin alerts are in-app only at first (Firebase push later); map = in-app Leaflet + OpenStreetMap (feature 2).
  Attendance rows are append-only and are their own upload queue (no sync_outbox / revisions).
- **Live location (feature 2, user decisions 2026-10-21):** its own module (`core/tracking/`, `feature/tracking/`),
  independent of attendance: check-in/out never depends on it. Only links: check-in turns sharing ON, check-out turns it
  OFF. Otherwise the engineer can switch sharing on without checking in (admin sees "Sharing · not checked in"). The admin
  can't switch it on remotely (no push; Android 14 forbids starting a location service from the background).
  Foreground service type `location`, started only from a tap (check-in or the switch), persistent notification with
  Pause. Fused platform provider, no Play services. Sampling: moving 60 s / 50 m balanced; still → nothing but a
  heartbeat every 10 min; battery ≤ 20 % → 5 min low power; live → 15 s high accuracy. Points stored on the phone first
  (own upload queue like attendance), uploaded in batches every 10 min (15 min on low battery, 30 s in live mode).
  Turning sharing off (except via check-out) needs a reason (Low battery / Break / Other + note) and alerts the admin;
  no time limit, no auto-resume — stays off until the engineer turns it on or checks out. Reboot: no
  background-location permission; a "Tap to resume" notification, admin sees "Stopped: phone restarted". Alerts are
  in-app only: phone-reported (paused, location off, permission removed, restarted, mock location, resumed) and
  server-derived "no location for 20 min" while sharing and not paused. Live mode: opening an engineer's detail marks
  them live for 10 min; the phone picks it up at its next upload (≤ 10 min). Engineers do NOT see their own route.
  Admin Team screen: alert strip, row per engineer (selfie/initials, status chip, battery, last seen), List/Map toggle
  with matching colours (Leaflet + OSM in a WebView), per-engineer day view (route + stops ≥ 5 min within 100 m).
  MIUI guide (Autostart, No restrictions, lock in recents) + ignore-battery-optimisation request. One-time consent
  notice, recorded in the audit log. Server deletes points after 90 days; phone deletes uploaded points after 7 days.
- **Calendar / leave (user decisions 2026-10-21):** weekly off = Sunday only, plus admin holidays. Marks: green = worked,
  red ring = leave, half-red = half day, blue ring = comp-off taken, yellow = missed check-in (circle) or missed check-out (square), grey =
  Sunday/holiday, ★ = comp-off earned. Leave types Casual / Sick / Comp-off, full or half day; no yearly balance. Comp-offs:
  no rules yet (no expiry, never blocks a request); earned automatically by checking in on a Sunday/holiday, admin can grant /
  take back. Days are India dates (fixed +05:30). Engineers request leave in the app (queued offline, DB v7); admin approves
  in the app or on the web, can record leave directly.
- **Web dashboard (user decisions 2026-10-21):** same Go server at `/web/` (no new host or Caddy change), same logins.
  Employees: report list + download PDF / Word for ALL inspections, nothing else. Admins: also calendar, leave, holidays.
  Word = editable copy following the PDF layout, built on the server (only latest version of a finalized inspection).
- Severity labels follow the prototype: Observation/Low/Moderate/High/Urgent (enum names are stored values).
- PDF: own ~300-line streaming writer (`PdfWriter`), no library (iText is AGPL). `PdfDocument` was measured and
  rejected: it holds every page's bitmaps until `writeTo()` and stores photos losslessly (30 photos → 59 MB PDF,
  230 MB RAM). Ours embeds the variant JPEGs byte-for-byte (40 photos → ~8 MB, ~13 MB RAM) and is deterministic.
  Trade-off: standard Helvetica = WinAnsi/Latin-1 text only (₹ → "Rs.", other scripts → "?").
- Editing a FINALIZED inspection (any write via `reportContentChanged`) moves it back to IN_PROGRESS; the next
  finalize writes version N+1 beside the shared file. Inspector name is a device pref until login (M4).

## Invariants — do not break
- A photo is "saved" only after: temp file fsync → SHA-256 → atomic rename → ONE Room transaction inserting
  the photo row + its `sync_outbox` UPLOAD_ORIGINAL entry. See `PhotoStore` / `PhotoRepository.commitCapture`.
- Saves that must outlive a screen run in `HomeInspectionApp.appScope` (photo commit, `AutoSaver`), never viewModelScope.
- Every write enqueues its outbox entry **in the same transaction** (`SyncOutboxDao.enqueue`).
- Deletes are tombstones (`deleted_at`); never hard-delete records or original files outside PRD §12 cleanup.
- Originals are immutable; report images are separate derived files.
- A new photo queues only UPLOAD_ORIGINAL; the sync engine pushes its metadata (revision 0) before the bytes.
- No destructive migrations. Any schema change bumps the DB version with a real `Migration` and a test;
  the exported schema lives in `app/schemas/`.
- New findings are created only together with their first saved photo.

## Build & run
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug        # or :app:assembleRelease, :app:testDebugUnitTest
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```
- In Claude's sandbox, Gradle's JVM can't reach the network: run Gradle commands with the sandbox disabled.
- Gradle 9.8.2 / AGP 9.4.1 (built-in Kotlin; KGP 2.4.21 pinned via root `apply false`), KSP 2.3.12, Room 2.8.5,
  CameraX 1.6.2 (opt-in `PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE`), Compose BOM 2026.09.00.

## Testing on the user's phone
- POCO F4 (Xiaomi, Android 14), connected over USB. Install + `pm grant` work.
- **Input injection is blocked** (Xiaomi): you cannot tap/type via adb. Ask the user to perform UI steps,
  then verify behind the scenes. Screenshots: `adb exec-out screencap -p` (screen must be unlocked).
- **Never start the emulator** (user, 2026-10-20: not enough RAM). Use the phone only (`ANDROID_SERIAL=81c5b68e`).
  Stop Gradle (`./gradlew --stop`) when done — the user asked to keep Java off when idle.
- Debug builds are `com.homeinspection.app.debug` ("Home Inspection (debug)", user-approved 2026-10-21): installed next to the
  release app with their own data, signed in as employee `TEST` (password in git-ignored `test-credentials.properties`).
  `run-as com.homeinspection.app.debug` works. Face/attendance logs: `adb logcat -s HomeInspectionFace`.
- Instrumented tests on the phone: never `:app:connectedDebugAndroidTest` (it uninstalls the app = data loss).
  Instead `assembleDebugAndroidTest`, `adb install -r` the test APK (Xiaomi shows an "Install via USB" prompt the user
  must tap), `am instrument -w -e class <tests> com.homeinspection.app.debug.test/androidx.test.runner.AndroidJUnitRunner`,
  then `adb uninstall com.homeinspection.app.debug.test` and delete test leftovers in the debug app's data
  (`databases/migration-test*`, `shared_prefs/retention-test-*`). Use bash for `tools/verify-device.sh`.
- `tools/verify-device.sh` — read-only DB pull + per-photo checksum/size/outbox check.
- `tools/kill-on-shutter.sh` — force-kills the app N seconds after each shutter press (crash tests).
- Debug builds only: a number in the app's private `files/debug_free_bytes` fakes free space
  (`adb shell "run-as com.homeinspection.app sh -c 'echo 400000000 > files/debug_free_bytes'"`); delete after.
- CameraX: unbind only your own use cases (`provider.unbind(...)`), never `unbindAll()` — the provider is app-wide and
  a closing screen's cleanup once blacked out the next camera screen during the navigation transition.
- **Never modify anything on the phone outside the Home Inspection app's own data.** The user asked for this explicitly.
- Never `adb uninstall` / clear app data — the user's test inspections live there.

## Working with this user
- Explain choices briefly with trade-offs; the user likes to understand *why* (e.g. limits, thresholds).
- Confirm before anything that touches the phone beyond installing/launching the debug build.
- Commit at the end of each verified milestone (conventional message + Co-Authored-By trailer).
