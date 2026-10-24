# Griha API v1

Base URL `https://api.grihahomeinspection.in`. JSON over HTTPS; client-neutral (Android today, iOS later).
Errors: `{"error": {"code": "…", "message": "…"}}` — branch on `code`, show `message`.
Auth: `Authorization: Bearer <token>` on everything except `/healthz` and `/v1/auth/login`.
Any `401` means the session is gone (expired, revoked, account disabled): sign in again; keep local data.

## Identity and conventions
- Entity ids are **UUIDs generated on the device**; the server never renumbers.
- Timestamps in entity data are **UTC epoch milliseconds** as the device recorded them.
- Field names are snake_case and equal the server columns (see `internal/api/sync.go`, `entities`).
- Deletes are tombstones: set `deleted_at`, never remove.

## Auth
| Method | Path | Body → Response |
|---|---|---|
| POST | `/v1/auth/login` | `{employee_code, password, device:{id (UUID), label, platform, app_version}}` → `{token, expires_at, user:{id, employee_code, display_name, role, must_change_password}, server_time}` |
| POST | `/v1/auth/logout` | → 204 |
| POST | `/v1/auth/password` | `{current_password, new_password}` → 204 (signs out the user's other devices) |
| GET | `/v1/me` | → `{user, server_time}` (use as the periodic "server check") |

Codes: `BAD_CREDENTIALS`, `ACCOUNT_DISABLED`, `DEVICE_REVOKED`, `TOO_MANY_ATTEMPTS`, `SESSION_INVALID`, `WEAK_PASSWORD`.
Sessions slide for 30 days. Offline use is a client policy (Android: 7 days since the last successful server contact,
local PBKDF2 verifier, never the password).

## Sync
**Push** `POST /v1/sync/push` — `{items:[{type, id, base_revision, mutation_id, force?, data:{…}}]}` (≤ 500 items).
- `type`: `INSPECTION | ROOM | FINDING | PHOTO | REPORT`. Send parents before children.
- `FINDING.kind`: `ISSUE` or `ROOM_PHOTOS` (a room's common photos: overview shots, no text). Optional; absent
  keeps the stored value, and a row that never had one (apps before 0.9) is an issue (`null` on pull).
- `base_revision`: the revision the client last got from the server (0 = never synced).
- `mutation_id`: unique per change, stable across retries (Android: `<idempotency_key>:<payload_version>`).
  A retry with the same id is acknowledged without re-applying.
- Result per item, same order: `{status, revision, code?, message?, server?}`
  - `OK` → store `revision`.
  - `CONFLICT` (`STALE_REVISION`) → another device changed it; `server` holds its copy. Resolve with the user:
    keep theirs, or push again with `base_revision = server.revision` (or `force: true`).
  - `REJECTED` → validation (`INVALID`, `IMMUTABLE_FIELD`, …); don't retry unchanged.
  - `RETRY` → transient (`PARENT_MISSING`, `INTERNAL`); back off and retry.

**Pull** `GET /v1/sync/changes?cursor=N&limit=500` → `{changes:[{type, data}], cursor, has_more}`.
Includes `USER` records (id, employee_code, display_name, role, status — never credentials) and server-only
fields: photos/reports carry `server_object_key`, `backup_verified_at`; photos also `backup_checksum`.
Apply all pages parents-first, then store `cursor`. Skip records with unsynced local edits.

## Files
| Method | Path | Notes |
|---|---|---|
| PUT | `/v1/photos/{id}/original` | Raw JPEG body, `Content-Length` = recorded `file_size_bytes`. Metadata must be pushed first (`409 METADATA_MISSING`). |
| PUT | `/v1/reports/{id}/pdf` | Same for finalized report PDFs (`content_hash`). |
| GET | `/v1/photos/{id}/original`, `/v1/reports/{id}/pdf` | Supports `Range` (resume). `X-Content-SHA256` header. |

Uploads are verified: the server streams to a temp file, checks size and SHA-256 against the recorded values
(`422 CHECKSUM_MISMATCH` otherwise), fsyncs, renames atomically, then answers
`{object_key, sha256, size, verified_at}`. Only then may a client mark the item "backed up". Re-uploading a
verified object returns the same answer (idempotent). Photo `sha256`/size/dimensions are immutable.

## Admin (role `ADMIN`)
`GET /v1/admin/users` · `POST /v1/admin/users {employee_code, display_name, role, password}` ·
`PATCH /v1/admin/users/{id} {display_name?, role?, status?: ACTIVE|DISABLED, password?, track_attendance?, attendance_from?}`.
Disabling or resetting a password revokes that user's sessions immediately. Admins can't disable/demote themselves.
`GET /v1/admin/inspections?q=` → per inspection: photos / backed up / exported / archived, reports backed up.
`GET /v1/admin/devices` → phones with their last `sync_report` · `PATCH /v1/admin/devices/{id} {revoked: bool}` (blocking ends its sessions).
`GET /v1/admin/audit?before=<id>&limit=100&type=` → newest first; `from_device` marks events uploaded by phones.
`PATCH /v1/admin/settings {retention_days? (7–365), archive_min_age_days? (30–3650), ai_daily_limit? (0–1000, 0 = AI off)}`.

### Phones (any signed-in user)
- `GET /v1/settings` → `{retention_days, archive_min_age_days, export_warn_bytes, policy_version, ai_enabled, ai_daily_limit}`
  (retention policy, PRD §5.9; `ai_enabled` = a key is set and the limit is > 0).
- Inspections carry `summary` (text, nullable): the report's overview paragraph. A push that omits the key keeps the stored
  value (apps before 0.6.0 don't send it); `null` clears it.
- `POST /v1/devices/report {app_version, pending_records, pending_photos, free_bytes, local_originals, local_originals_bytes,
  cleanup_blocked, problems:[{type,id,op,code,message,attempts}] (≤50), audit:[{id (UUID), type, entity_type, entity_id, details, created_at}] (≤200)}`
  → `{accepted:[ids]}`. Sent after each sync; audit events are stored once per id.
- `POST /v1/photos/check {ids (≤500)}` → `{results:{id:{status}}}`, status `OK | ARCHIVED | MISSING | NOT_VERIFIED | SIZE_MISMATCH`.
  Phones ask right before retention cleanup and keep their originals unless OK (or ARCHIVED with a verified export).
- Photos/reports in pulls also carry `export_verified_at` and `archived_at`. `GET` of an archived file → `410 ARCHIVED {export_id}`.

### Storage, exports, archive (role `ADMIN`)
- `GET /v1/admin/storage` → disk free/total of DATA_DIR, `objects_bytes`, `unexported_bytes/count`, `archived_bytes`, `last_export_at`,
  `export_warn_bytes` (5 GB), recent `exports`. `/healthz` also returns `data_free_bytes`.
- `POST /v1/admin/exports` → freezes the next batch: oldest month (by inspection date) of backed-up files not in a verified export,
  whole inspections, ~1.5 GB. Every file is re-hashed first; altered/missing ones are left out and listed in `problems`
  (audit `BACKUP_INTEGRITY_FAILED`). While an export is unverified, the same export is returned (no overlaps). `409 NOTHING_TO_EXPORT`.
- Zip: Store method, deterministic bytes (Content-Length, `Range` resume, `ETag` = manifest SHA-256). Entries: `manifest.csv`
  (path, sha256, size, inspection_id, captured_at), `data.json` (all records of those inspections, nested), `photos/<insp>/<id>.jpg`,
  `reports/<insp>/<id>.pdf`. Download: `GET /v1/admin/exports/{id}/zip` (bearer) or `POST …/{id}/link` → `{path}` = `/v1/export-files/<token>`
  (no bearer, 48 h, one export; used by Android's DownloadManager). `410 EXPORT_ARCHIVED` once files were removed.
- `POST /v1/admin/exports/{id}/verified {manifest_sha256, entries_verified}` → after the client re-hashed every entry. Sets
  `export_verified_at` on its photos/reports (phones may then clean up). `409 MANIFEST_MISMATCH` otherwise.
- `POST /v1/admin/exports/{id}/archive` → deletes from disk the files of this verified export whose inspection is FINALIZED and
  finalized ≥ `archive_min_age_days` ago; records stay (`archived_at`). Repeatable (archives newly old ones, sweeps leftovers).
  Returns `archived_now, kept_too_recent, kept_not_finalized, freed_bytes`.

## AI writing help (DeepSeek, via the server)
The phone never holds the key; the server adds it and the instructions (prompts in `internal/api/ai.go`), calls
`deepseek-flash` in JSON mode, and returns suggestions the inspector accepts or discards. Nothing here writes records.
Sent upstream: room names, property type, finding text, photo captions. Never client name/phone/address or photos.
- `GET /v1/admin/ai` (admin) → `{available, key_set, key_last4?, key_set_at?, key_set_by?, model, daily_limit, month_requests,
  month_tokens, users:[{name, requests}]}`. The key itself is never returned.
- `PUT /v1/admin/ai/key {key}` (admin) → checked with DeepSeek `GET /models` first (`400 AI_KEY_REJECTED`), sealed with
  AES-256-GCM under `GRIHA_SECRET_KEY` (env, 64 hex chars; `503 AI_UNAVAILABLE` if unset). Audit `AI_KEY_SET`.
  `DELETE /v1/admin/ai/key` → audit `AI_KEY_REMOVED`.
- `POST /v1/ai/finding {room, property_type, category, severity, title, site_note, observation, implication, recommendation,
  photo_captions[], categories[]}` → `{title, observation, implication, recommendation, severity?, severity_reason?, category?}`
  (severity = stored enum name; category only from `categories`).
- `POST /v1/ai/summary {property_type, rooms:[{name, findings:[{id, title, severity, category, observation, implication,
  recommendation}]}]}` → `{summary}`.
- `POST /v1/ai/review` (same body, findings also with `site_note`) → `{issues:[{finding_id, kind, message}]}`, kind
  `MISSING_DETAIL | SEVERITY | DUPLICATE | UNCLEAR`. Uses low-effort thinking. Server deadline 150 s (finding/summary 60 s);
  the phone waits 180 s / 90 s.
- Errors: `503 AI_OFF`, `429 AI_LIMIT` (per user per India day), `502 AI_KEY_INVALID | AI_NO_BALANCE | AI_FAILED | AI_UNREACHABLE`,
  `503 AI_BUSY`, `504 AI_TIMEOUT`; messages are written for the inspector. Usage per user/day/kind in `ai_usage`.
  The limit is reserved atomically before calling DeepSeek (per-user advisory lock), so parallel requests can't exceed it;
  `failures` = refunded requests (DeepSeek unreachable or answered non-200: nothing billed), and the limit counts
  `requests - failures`.

## Attendance (face check-in/out, migration 005)
Face matching runs **on the phone** (OpenCV YuNet detector + SFace recognizer, Apache 2.0); the server stores approved
embeddings and the evidence, and never runs a model. Engineers see only their own records; admins see everyone's.
- `GET /v1/face/model` → the SFace ONNX file from `DATA_DIR/models/face_recognition_sface_2021dec_int8.onnx` (9.9 MB, Range-resumable,
  `404 MODEL_MISSING` if not installed). The phone pins its SHA-256.
- `POST /v1/face/enrollments {id, model:"sface-2021dec-int8", embeddings:[[128 floats] × 1–10], photo_sha256, photo_size}` →
  PENDING (replaces the caller's older PENDING; the APPROVED one keeps working until a new approval). Retry = no-op.
  Then `PUT /v1/face/enrollments/{id}/photo` (JPEG, verified like photos). `GET .../photo`: owner or admin.
- `GET /v1/face/me` → `{model, approved, pending, rejected}`; only `approved` carries `embeddings`. Enrolments of any
  other model are ignored here and in the admin list (embeddings don't compare across models).
- `GET /v1/admin/face/enrollments` (PENDING first, then APPROVED, with `employee_code`, `display_name`, `shots`).
  `POST /v1/admin/face/enrollments/{id}/decision {approve}`: approve needs the photo uploaded (`409 PHOTO_MISSING`);
  `approve:false` on an APPROVED one resets the engineer's face. Audit `FACE_ENROLLED`, `FACE_APPROVED`, `FACE_REJECTED`.
- `POST /v1/attendance {id, user_id, kind: IN|OUT, client_at, time_zone, elapsed_ms, boot_count, now_elapsed_ms, now_boot_count,
  lat?, lng?, accuracy_m?, location_at?, is_mock, face_result: MATCH|WEAK|NO_MATCH|NOT_ENROLLED|UNAVAILABLE, face_score?,
  enrollment_id?, liveness_passed, selfie_sha256, selfie_size}`. Retry = no-op. `user_id` ≠ caller →
  `403 WRONG_USER` (a shared phone never files one engineer's offline check-in under another's session). `estimated_at` = server time minus the
  phone's monotonic time since the event (null if it rebooted in between): compare with `client_at` to spot a changed
  clock. Then `PUT /v1/attendance/{id}/selfie`; `GET` it as owner or admin (`410 EXPIRED` after retention).
- `GET /v1/attendance?from=&to=` (own) · `GET /v1/admin/attendance?from=&to=[&user=]` (epoch ms, ≤ 62 days) →
  `{records:[{..., selfie: AVAILABLE|UPLOADING|DELETED}]}`.
- Face result and liveness are **reported by the phone** (accepted risk: a modified APK could fake them; the admin
  compares the selfie with the approved photo, and the server can't run a model — Go-only).
- Selfies are deleted 90 days after arrival (checked every 6 h, audit `SELFIES_PURGED`); records and enrolment photos stay.

## Live location (migration 006)
Independent of attendance (the phone starts sharing at check-in and stops at check-out; nothing on the server links them).
- `POST /v1/tracking/batch` `{"user_id", "points": [{"id", "client_at", "lat", "lng", "accuracy_m", "battery", "is_mock"}],
  "events": [{"id", "kind", "source", "reason", "note", "client_at"}]}` → `{"skipped", "live_until", "server_now"}`.
  Ids are client UUIDs (retries are no-ops). Invalid rows are skipped and counted, never the whole batch. ≤ 2000 points,
  ≤ 200 events. `user_id` ≠ session → `403 WRONG_USER`. Event kinds: `START` (source `CHECK_IN|MANUAL|RESUME|RESTORE`),
  `STOP` (source `CHECK_OUT`), `PAUSE` (reason `LOW_BATTERY|BREAK|OTHER` + note), `INTERRUPTED` (reason
  `REBOOT|KILLED|PERMISSION_REMOVED`), `LOCATION_OFF|LOCATION_ON`, `MOCK`, `CONSENT` (note = notice version).
  PAUSE, INTERRUPTED and CONSENT are audited. `live_until` (epoch ms) = an admin is watching: sample 15 s, upload 30 s.
- `GET /v1/admin/tracking/team` → `{"engineers": [...]}`, one row per active user: state `OFF | SHARING | PAUSED |
  INTERRUPTED | LOCATION_OFF | STALE` (no upload for 20 min, 30 min when the last battery reading ≤ 20 %, by server clock),
  reason/note, latest point + battery, `last_contact`, `on_duty` (latest attendance), `face_id` (approved enrolment photo),
  `mock_today`, `live_until`.
- `GET /v1/admin/tracking/day?user=&from=&to=` (≤ 2 days) → `{"points", "events"}`; events include check-ins/outs as
  `CHECK_IN`/`CHECK_OUT` with their location.
- `POST /v1/admin/tracking/live {"user_id"}` → watch for 10 min; the phone switches at its next upload (≤ 10 min, no push).
- Points and events are deleted 90 days after arrival (6-hourly, audited as `LOCATIONS_PURGED`).

## Attendance calendar, leave and comp-offs (migrations 007, 009)
Days are company-local dates (India, fixed +05:30). **Sunday** is the weekly off; admins add holidays. A day's status is derived
on request (never stored): `WORKED` (checked in and out) · `MISSED_CHECKOUT` (a past day with a check-in and no check-out) ·
`MISSED` (past tracked working day, no work, no leave) · `LEAVE` (approved full day; `leave.kind` `COMP_OFF` = comp-off taken) ·
`WORKED_ON_LEAVE` (approved full day but worked: counted as worked, not leave) · `HALF_LEAVE` (approved half day; `half_work`
= `WORKED|MISSED|MISSED_CHECKOUT` for the other half, `""` today/future) · `OFF` (Sunday/holiday) · `TODAY` · `FUTURE` ·
`NONE` (not tracked that day).
**Who is tracked:** `users.track_attendance` (new inspectors on, admins off) from `users.attendance_from` (joining day,
default the day the account was made; existing accounts from 2026-10-21) until the day the account was disabled.
**Event time** = the server's estimate from the phone's monotonic clock (`estimated_at`) when there is one, else the phone's
clock; more than 5 min apart → `clock_skew` on the day. **Hours**: an IN is closed by the next OUT up to 16 h later (a second IN
while in is ignored unless the first is older than 16 h); the time is split at midnight, so a night shift counts on both days
(`overnight` on the second). Comp-offs are **earned automatically** by work on a Sunday/holiday (`comp_off_check`: `SHORT` =
under an hour, `CLOCK` = phone clock off); an admin can `GRANT` one for any day, `REVOKE` an automatic one, or `CLEAR` back to the
rule. No yearly balance and no comp-off rules (user decision): the comp-off balance is shown (also where the admin approves),
never enforced.
- `GET /v1/calendar?month=YYYY-MM` (own) · `GET /v1/admin/calendar?month=&user=` →
  `{user:{…, track_attendance, attendance_from, left_on}, month, today, days:[{day, status, half_work?, off_day, holiday?, first_in,
  last_out, worked_ms, overnight, clock_skew, leave, pending_leave, comp_off?, comp_off_check?}],
  totals:{days_worked, worked_ms, working_days, missed, missed_checkouts, leave_days, leave_by_kind, worked_on_leave, comp_offs_earned},
  comp_off:{earned, used, pending, balance}}`.
- `GET /v1/admin/calendar/team?month=` → `{month, today, users:[{user, days:[mark…], pending:[day…], totals}], holidays,
  comp_offs:[{user_id, display_name, day, kind: EARNED|GRANTED, check, worked_ms}]}`. Users: tracked, started by the month's end,
  not disabled before its start. Marks = status, but `COMP_OFF` for comp-off days and `HALF_LEAVE_<half_work>` when known.
- `GET /v1/admin/calendar/export?month=` → `Griha-Attendance-YYYY-MM.xlsx` (web too): sheet Summary (one row per engineer:
  working days, days and hours worked, missed check-ins/outs, leave by kind, comp-offs earned, comp-off balance today) and sheet
  Day by day (date, status, check-in/out in India time, hours, leave, holiday, comp-off, notes; future days left out). Audited.
- `POST /v1/leave {id, user_id?, kind: CASUAL|SICK|COMP_OFF, start_day, end_day, half?: FIRST|SECOND, reason}` → PENDING.
  Retry = no-op; `409 OVERLAP` with own pending/approved leave; ≤ 62 days; back-dated ≤ 60 days; half = single day;
  `400 NO_WORKING_DAYS` when every day is a Sunday or holiday.
  `GET /v1/leave` → `{requests, comp_off, today}` · `POST /v1/leave/{id}/cancel` (pending, or approved not yet started).
- Admin: `GET /v1/admin/leave?status=&user=` → `{requests, pending[, comp_off]}` (pending first); each request also has
  `worked_days` (days in it with work) and, for comp-off requests, the engineer's `comp_off` balance ·
  `POST /v1/admin/leave/{id}/decision {approve, note}`: PENDING → APPROVED | REJECTED; APPROVED → REVOKED (taken back);
  REJECTED/REVOKED → APPROVED (overlap-checked). CANCELLED is final. An admin's own request needs another admin
  (`403 SELF_DECISION`) unless they are the only one (audited "self-decided") ·
  `POST /v1/admin/leave {id, user_id, kind, …}` records leave approved at once (`by_admin`); over an engineer's pending
  request → `409 OVERLAP` "approve it instead".
- `GET /v1/holidays?year=` (anyone) · `PUT /v1/admin/holidays/{day} {name}` (2020–2100) · `DELETE /v1/admin/holidays/{day}` —
  both → `{day, …, affects}` = people whose comp-offs or leave days the change touches ·
  `POST /v1/admin/comp-offs {user_id, day, action: GRANT|REVOKE|CLEAR, note?}` → `{comp_off}`.
- `PATCH /v1/admin/users/{id}` also takes `track_attendance` and `attendance_from` (YYYY-MM-DD); `GET /v1/admin/users` returns them.
- Audit: `LEAVE_REQUESTED|APPROVED|REJECTED|REVOKED|CANCELLED|RECORDED`, `HOLIDAY_SET|REMOVED`, `COMP_OFF_GRANT|REVOKE|CLEAR`.

## Web dashboard (`/web/`, same server)
Plain HTML/JS embedded in the binary (`internal/api/web/`), strict CSP (no inline script or style), HSTS. `GET /` redirects to `/web/`.
The static files stay reachable under the developer lock; every API call still gets the 423.
- `POST /v1/auth/web-login {employee_code, password}` + header `X-Griha-Web: 1` → `{user}` and an HttpOnly, Secure,
  SameSite=Strict cookie `__Host-griha_web`. Same rate gate, lockouts and password rules as the app; `sessions.web = true`, no device,
  ends after 12 idle hours (90-day cap as for phones). A web cookie is never accepted as a bearer token and vice versa.
  Non-GET requests with the cookie need `X-Griha-Web: 1` (a cross-site form can't send it). `POST /v1/auth/logout` clears it.
  Web sessions only reach the dashboard's routes (`webRoutes` in `web.go`); sync, uploads, attendance, tracking, face, AI → `403 NOT_ON_WEB`.
  Word photos over 50 MP are skipped (placeholder) instead of decoded.
- Everyone: `GET /v1/reports?q=&before=` → `{inspections, next}`, 200 per page (`next` = the `before` of the following page,
  `""` after the last); each inspection has its latest report and `versions` (newest first) `{id, version, pdf:
  AVAILABLE|UPLOADING|ARCHIVED, docx, docx_reason?}`. `GET /v1/reports/{id}/pdf` works for any version (named
  `Griha-Report-<number>-v<n>.pdf` for the web) and `GET /v1/reports/{id}/docx`: an editable Word copy following the PDF layout
  (report-size photos 1600 px q80 with EXIF rotation, like the phone), built from the version's **content snapshot** — so any
  version, matching its PDF. Versions without one (finalized before 0.9.0) only for the latest version of a FINALIZED inspection,
  from the live data (`409 NOT_LATEST`). `409 PHOTOS_UPLOADING` while photos are missing; archived photos show a placeholder.
  One build at a time; cached in `DATA_DIR/cache/docx` by a fingerprint of the content, unused files removed after 7 days. Audit `REPORT_DOCX`.
- `PUT /v1/reports/{id}/content` (phones; the user who pushed the version, or an admin): the finalizing phone's JSON of what the
  PDF showed `{format: 1, report_id, inspection_id, version, report_number, client_*, property_*, unit_number, inspection_date,
  inspector_name, summary, rooms:[{name, findings:[{title, category, severity, status, observation, implication, recommendation,
  photos:[{id, sha256, caption}]}]}]}` ≤ 4 MB. Stored once (`reports.content_json`); the same bytes again = 200, different = `409
  CONTENT_EXISTS`; `404` until the version's metadata is pushed.
- Admins: Attendance (team month → person month → day: times, leave, grant/take back comp-off, record leave; comp-offs of the
  month), Leave approvals (with worked days and comp-off balance), Holidays — all on the endpoints above.

## Security notes (audit 2026-10-21, `review/security-audit-0.7.0.md`)
- Login: ≤ 10 attempts per IP per minute, checked before PBKDF2 (`429 TOO_MANY_ATTEMPTS`); then the per-(code, IP) and
  per-account failure counters. The per-account lock does not apply to a phone already registered to that user, so
  strangers can't lock its owner out.
- `must_change_password`: every authenticated call except `POST /v1/auth/password`, `POST /v1/auth/logout`, `GET /v1/me`
  answers `403 PASSWORD_CHANGE_REQUIRED`. New passwords: ≥ 10 characters.
- Sessions: 30 days sliding, and never older than 90 days.
- Export links: valid 30 minutes after their last use (each request extends it).
- AI: the phone removes the inspection's client name/phone/address; the server removes phone numbers and emails.
- Accepted risks: blocking a device doesn't stop a person (disable the account); `griha-api` shares the
  `bondverse_default` network with the other apps' containers, which can reach it directly and set `X-Real-IP` (other
  containers on the box are trusted); every inspector can read and edit every inspection (recorded decision); phone data
  is protected by Android's file encryption and the device lock, not by the app.
- Dependencies: Go 1.26 build image; run `govulncheck ./...` on the server with each server change (clean 2026-10-21).

## Developer lock (server-only switch)
- `GET /v1/app/status` (no auth) → `200 {"locked": false}`. Phones call it on every app start; it is the only thing
  that unlocks a locked phone (a 200 whose `locked` is the JSON boolean `false`).
- While locked, **every** route except `/healthz` answers `423 {"error": {"code": "APP_LOCKED", "message": "..."}}`,
  before authentication. Phones lock only when status 423 *and* code `APP_LOCKED` match; anything else changes nothing.
- No HTTP endpoint changes the lock and the client's admins can't see it (history in `app_lock_history`, not `audit_events`).
  Locking deletes nothing: sessions, records and files stay; phones keep unsynced work queued until an unlock.
- `docker exec griha-api /griha-api lock "Message shown on the phones"` · `... unlock` · `... lock-status`.
  The API re-reads the switch at most every 3 s.

## Operations
- Lives in `/root/apps/griha` on the Hetzner box: `docker-compose.yml`, `.env` (DB password, `GRIHA_SECRET_KEY`), `data/objects`,
  `data/postgres`, `src/` (rsync of this folder). Caddy block appended to `/opt/bondverse/Caddyfile`.
- Tests: `sh test.sh` (throwaway Postgres). Deploy: rsync `server/` → `src/`, `docker compose up -d --build griha-api`.
- Bootstrap a user: `echo PASSWORD | docker exec -i griha-api /griha-api create-user CODE "Name" ADMIN`.

### Backups
- Weekly, Sunday 02:30 IST: `/etc/cron.d/griha-backup` (copy in `deploy/griha-backup.cron`) runs `sh backup.sh`,
  log `backups/backup.log`, last result `backups/last-backup.txt`. `backups/` is mode 700 (dumps hold password hashes).
- `backup.sh`: `pg_dump -Fc` → `backups/db/griha-<UTC stamp>.dump`, checked with `pg_restore --list`, newest 8 kept.
  Optional off-site copy: create `rclone/rclone.conf` (mode 600) with a remote named `offsite` — intended for a
  dedicated backup Google account, never a personal one (`scope = drive.file`). Objects go up with `--ignore-existing`
  (immutable, never deleted remotely); remote dumps older than 90 days are pruned. Without the file it is skipped.
- `sh restore-test.sh [objects_dir]`: restores the newest dump into a throwaway Postgres (tmpfs, `--network none`) and
  checks every verified photo/report object against the restored SHA-256; exits 1 on any MISSING/MISMATCH.
  Tested 2026-10-20: 18/18 objects OK; a deliberately corrupted copy was reported as MISMATCH.
- Real restore: `docker compose stop griha-api`, recreate the DB (`dropdb`/`createdb` in griha-postgres),
  `docker exec -i griha-postgres pg_restore -U griha -d griha --no-owner < dump`, copy objects back into `data/objects`,
  `docker compose start griha-api`.
- Long-term storage: admin export/archive (M5) — zips downloaded from the app and kept off the server.
