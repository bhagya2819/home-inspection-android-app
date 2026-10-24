# Security audit: Home Inspection app 0.7.0 + server (read-only review)

Scope: Android manifest/storage/session code, Go API (auth, sync, files, exports, admin, AI), deploy files.
Method: code reading only. Nothing was run, no server or phone touched, no secrets read (release keystore never opened).
Threat model assumed: internet attacker against `api.homeinspection.example`; a lost/stolen inspector phone; a malicious or careless
inspector with valid credentials; a leaked database/backup.

**Overall: sound for a small pilot. No critical or remotely exploitable hole found.** The fundamentals are right
(hashed passwords, hashed session tokens, per-request session/device check, Keystore-encrypted token, no cleartext, no backups, narrow FileProvider).
The gaps are availability/abuse controls and a few design trade-offs.

## Findings (most severe first)

### S1. MEDIUM: AI daily limit can be bypassed by parallel requests (spend control)
`server/internal/api/ai.go` `runAI`: check, then call, then record. A stolen token or a scripted inspector can fire many concurrent
`/v1/ai/review` calls before any usage row exists. Also usage is skipped if the client disconnects (request context cancelled).
Impact: DeepSeek bill, not data. Fix: reserve the request atomically before the call; record with `context.WithoutCancel`.
(Same finding as code-review #1/#2.)

### S2. MEDIUM: unauthenticated CPU/memory pressure on `/v1/auth/login`
PBKDF2 600k iterations (~0.2 s per attempt on the 2-vCPU box). For an unknown employee code the server still runs a full dummy hash,
and the limiter allows 5 failures per `(code, IP)` pair, so an attacker rotating random codes gets unlimited ~0.2 s attempts
(each creates a limiter entry, capped at 50,000 keys, then new keys are simply not tracked). A few requests per second would saturate
the CPU of a box shared with other apps; Caddy has no rate limit configured.
Fix: add a global/per-IP login rate limit independent of the code (per IP, e.g. 10 attempts/min, counted before hashing),
and/or a Caddy `rate_limit`/fail2ban rule.

### S3. MEDIUM: any account can be locked out by anyone who knows its code
Per-account counter: 15 failures from anywhere starts escalating lockouts (+1 min per extra failure, remembered 15 min).
Employee codes are short and guessable; an attacker can keep the admin (or an inspector on site) locked out indefinitely with a trickle of requests.
Trade-off versus brute-force protection; the offline 7-day sign-in softens it for inspectors, not for admin actions.
Fix: lock out per `(code, IP)` and per IP only, or make the per-account lock soft (add delay, never reject a request from a device already known for that user).

### S4. LOW-MEDIUM: `must_change_password` is enforced only by the app
`auth.go` loads the flag but no middleware rejects other calls. A user (or someone who learned a temporary password from the admin)
can use the API fully without ever changing it by calling the API directly. Fix: in `auth()`, return 403 `PASSWORD_CHANGE_REQUIRED`
for everything except `/v1/auth/password`, `/v1/auth/logout`, `/v1/me`.

### S5. LOW-MEDIUM: device revocation is bypassable with valid credentials
`login` upserts the device by the client-supplied UUID and refuses only if *that* UUID is revoked. A blocked phone (or anyone with the password)
logs in again with a fresh UUID. Also `ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id` lets any user adopt another device's UUID
(needs the UUID, which only admins/the device see). Revoking a device is therefore weaker than the UI implies; **disabling the account** is the
reliable control. Fix: document that, or bind a revoked-device list to user (block new devices for a user flagged by admin), and don't re-assign `user_id` of an existing device.

### S6. LOW-MEDIUM: client data is processed by DeepSeek (privacy/compliance, not a bug)
Room names, property type, site notes, titles, observations, captions are sent to DeepSeek's API (operated from China; retention governed by their terms).
The app promises "never client name/phone/address", but site notes and photo captions are free text and inspectors may type names, flat numbers or phone numbers there.
Consider: a one-line warning on the AI button, a server-side scrub (regex for phone numbers, the inspection's own client name/address passed
from the server DB and removed from text), and a check against India's DPDP Act obligations / a privacy-policy line for clients. Disable AI by setting the daily limit to 0 if this is unacceptable.

### S7. LOW: export download link is a bearer token in a URL, valid 48 h
`/v1/export-files/{token}` needs no login (so the Android DownloadManager can fetch it) and the zip holds every client's name, phone, address and photos
for that month. The token is 256-bit random and stored only as SHA-256 (good), the app log path is redacted, and a new link replaces the old one,
but the URL may land in Caddy access logs, browser history or shared chats. 48 h is long. Fix: shorten to 15-30 min, make it single-use
(clear `download_token_hash` on first completed GET, allowing Range resume within a short window), keep Caddy access logging of that path off.
The downloaded zip then sits in the phone's Downloads/Home Inspection (visible to other apps with storage access and to the user): unavoidable with DownloadManager; note for the admin.

### S8. LOW: `X-Real-IP` trusted; API reachable from other containers
The limiter keys on `X-Real-IP`, safe only because Caddy is the sole ingress. `home-inspection-api` is also attached to the shared `bondverse_default` network,
so any other container on that network can call `home-inspection-api:8080` directly with a forged header (resets per-IP limits) or skip TLS/body limits.
Postgres is correctly only on the private network and no ports are published. Fix: a dedicated network between Caddy and home-inspection-api,
or accept that other containers on that box are trusted.

### S9. LOW: whole-company data model (by design)
Every authenticated inspector can read/modify every inspection, download any photo/PDF, and overwrite any record (the server only checks revisions, not ownership);
`inspector_user_id` is client-supplied. This is the recorded decision ("everyone sees all inspections"), so it is a risk acceptance, not a bug: a disgruntled
inspector can delete (tombstone) others' work (recoverable from audit/history only partially). The per-user restriction switch is the planned fix.

### S10. LOW: data at rest on the phone
Room DB, photos and report PDFs are plain files in app-private storage (not encrypted beyond Android's file-based encryption). Session token and offline verifier
are Keystore-encrypted (good). Backups are disabled (`allowBackup=false`, extraction rules exclude everything). A rooted/unlocked phone exposes client data;
mitigate with device lock policy. The offline verifier uses 210k PBKDF2 rounds (below the server's 600k, an acceptable trade).

### S11. LOW: operational secrets
`HOME_INSPECTION_SECRET_KEY` and `POSTGRES_PASSWORD` sit together in `/root/apps/home-inspection/.env` on the same host as the DB, so the AES-GCM sealing protects the DeepSeek key only against a
database/backup-only leak, not a host compromise. That is the intended design; make sure DB backups never include `.env`.
The audit log stores the last 4 characters of the DeepSeek key (fine). The release keystore and password are correctly outside the repo and `.gitignore`d (`*.jks`).

### S12. NIT / hardening ideas
- Add response headers (`X-Content-Type-Options: nosniff`, `Cache-Control: no-store` on authenticated JSON) in `writeJSON`.
- `login` returns different errors for a disabled account only after a correct password (good: no account enumeration). Keep it.
- Password policy is 8 characters minimum with no breach/common-password check; admin-created passwords are the weakest link. Consider 10+ or generated passwords.
- Sessions live 30 days sliding with no absolute cap and no "log out everywhere" for the user (admin password reset does revoke all).
- Server has no `http.Server` body-read timeout (intentional for slow uploads); slowloris on upload endpoints is bounded only by `ReadHeaderTimeout`. Caddy mitigates.
- Dependency hygiene: pin and periodically `govulncheck` (on the server, not the Mac); Android deps are on current versions.

## Checked and found fine
- Passwords: PBKDF2-HMAC-SHA256, 600k iterations, 16-byte random salt, constant-time compare, dummy hash for unknown users (no timing oracle), no plaintext anywhere.
- Sessions: 256-bit random tokens, only SHA-256 stored, checked against user status AND device revocation on every request; password change/reset/disable revoke sessions.
- Authorization: all `/v1/admin/*` and AI-key routes wrapped in `s.admin`; role read from DB per request, not from the token.
- SQL: all values parameterised; the few `fmt.Sprintf` SQL fragments use static table/column names from the in-code entity list, never request data.
- File handling: object paths built from DB-held UUIDs and a fixed directory, no client-supplied path components; uploads streamed to temp, size/SHA-256 verified, immutable once verified;
  upload size bounded by recorded size and `MaxBytesReader`.
- AI key: sealed AES-256-GCM, write-only, never returned or logged; DeepSeek URL/model only from server env (no SSRF via API).
- Request bodies capped everywhere with `readJSON`; errors are fixed messages (no stack traces/SQL).
- Logs: no bodies, tokens or query strings; export token path redacted.
- Android: `allowBackup=false`; only `MainActivity` (launcher) is exported; the sync service requires BIND_JOB_SERVICE; FileProvider is not exported and exposes `files/reports/` only;
  no cleartext traffic (HTTPS base URL, targetSdk 37 default blocks http); no `Log` calls of secrets; debug-only hooks gated by `BuildConfig.DEBUG`; release is minified and signed with the user's key
  (build script refuses debug-signed releases).
- Brute force on login: per (code,IP) and per-account throttling with bounded memory (see S2/S3 for the remaining gaps).

## Suggested order
1. S2 (login rate limit per IP) and S1 (atomic AI limit): cheap, closes the two real abuse paths.
2. S4 (enforce password change server-side) and S7 (shorter, single-use export links).
3. S6: decide how to handle personal data in free text sent to DeepSeek before the pilot starts.
4. S3/S5/S8/S9 as the user base grows (before more than a handful of inspectors).
