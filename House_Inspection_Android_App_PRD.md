# Product Requirements Document (PRD)

## Offline-First Android House Inspection & Reporting Application

**Document status:** Planning baseline --- ready for technical design\
**Version:** 1.0\
**Date:** 19 October 2026\
**Platform:** Android only (private APK distribution; no Play Store
publication required)

------------------------------------------------------------------------

## 1. Executive Summary

Build a fast, reliable, mobile-first Android application for a
professional house-inspection team. Inspectors record property details,
inspect rooms or custom areas, capture high-quality defect photos, add
quick field notes, refine findings later, generate a professional PDF on
the phone, and share it through Android's native sharing interface
(WhatsApp, email, and compatible apps).

The application must work offline after an employee has been authorized
on the device. Inspection data and original photos are saved locally
first. A background synchronization process sends records and photos to
an existing VPS/cloud server, which acts as a central hub for
authentication, shared records, and backups---not as a processing
engine. PDF generation, image resizing for reports, previews, and other
user-facing computation should happen on the phone.

**Highest-priority product principle:** never report a photo as safely
saved or backed up unless that state has been verified. Network loss
must not block an inspection or silently discard evidence.

## 2. Goals and Non-Goals

### 2.1 Goals

1.  Capture and preserve high-quality original inspection photos.
2.  Create and edit inspections fully offline after initial
    authorization.
3.  Organize inspections by property, room/area, and individual finding.
4.  Save rough notes immediately and support detailed editing after the
    site visit.
5.  Generate and preview PDFs on the device, including without internet
    access.
6.  Share reports through Android's native share sheet.
7.  Synchronize records and images reliably to the existing server.
8.  Allow administrators to manage employee accounts and permissions.
9.  Browse prior inspections and reports from authorized devices.
10. Minimize server CPU, memory, and processing load.
11. Provide safe, auditable retention and cleanup of local originals
    after verified backup.
12. Maintain high responsiveness, battery efficiency, and robust
    recovery from app/process/device interruptions.

### 2.2 Non-Goals for the initial release

-   iOS application.
-   Public Play Store listing.
-   Server-side PDF generation or server-side image processing.
-   Real-time collaboration or simultaneous editing of one inspection.
-   AI-based defect detection or automatic report writing.
-   Direct WhatsApp Business API integration. Sharing uses the Android
    share sheet.
-   Mandatory cloud connectivity while inspecting.
-   Automatic permanent deletion without explicit retention checks and
    user-visible status.

These can be considered later without making the initial architecture
dependent on them.

## 3. Users and Roles

### 3.1 Administrator

-   Create, disable, and reactivate employee accounts.
-   Issue or reset credentials through a secure process.
-   Assign roles and inspection access.
-   View permitted inspections, synchronization status, and backup
    status.
-   Configure inspection templates, default rooms, categories, severity
    levels, and retention policy.
-   Review audit logs and failed synchronization items.

### 3.2 Inspector / Employee

-   Sign in online initially and establish an authorized local session.
-   Reopen the app offline using a previously authorized local session.
-   Create, resume, and edit permitted inspections.
-   Capture photos and write notes offline.
-   Add, rename, reorder, or remove custom room/area entries when
    allowed.
-   Refine findings and generate PDFs.
-   Share reports and view synchronization status.
-   See clear warnings when storage is low, backup is pending, or local
    cleanup is blocked.

### 3.3 Permissions

Use server-enforced role-based access control. The client must not be
trusted to enforce authorization by itself. Local permissions are a
usability and offline-safety layer; server authorization remains
authoritative whenever connected.

------------------------------------------------------------------------

## 4. Core User Workflow

1.  Employee signs in and receives an authorized session plus the
    minimum data needed for offline operation.
2.  Employee starts a new inspection.
3.  Employee enters client and property details.
4.  Employee selects a default room or adds a custom area (for example,
    sit-out, terrace, utility area, or external wall).
5.  Employee creates one or more findings in the selected area.
6.  For each finding, employee captures one or more photos, adds a short
    note, and optionally sets a category and severity.
7.  The app immediately saves local records and original photo files.
    Work continues without internet.
8.  Employee leaves the inspection and can resume later without losing
    progress.
9.  Employee refines rough notes into clear descriptions, implications,
    and recommendations.
10. Employee reviews a locally generated PDF, regenerates it after
    edits, and shares it.
11. The app syncs structured data and photo files in the background when
    connectivity returns.
12. The app verifies server backup and shows per-item sync state.
13. Local photo cleanup is permitted only when the retention policy and
    safety conditions are satisfied.

### Required navigation

-   Login / unlock
-   Inspections dashboard
-   New inspection / property details
-   Room and area list
-   Finding editor and camera capture
-   Inspection review
-   Report editor
-   PDF preview and sharing
-   Sync and storage status
-   Inspection history
-   Admin/user management (admin role)
-   Settings, privacy, retention, and diagnostics

------------------------------------------------------------------------

## 5. Functional Requirements

Priority labels: **P0** = launch blocker; **P1** = important; **P2** =
later enhancement.

### 5.1 Authentication and account management

-   **P0:** Employee ID/username and password login.
-   **P0:** Admin can create, disable, and reset employee accounts.
-   **P0:** Passwords are hashed server-side; plaintext passwords are
    never stored.
-   **P0:** Online authentication is required for first authorization on
    a device.
-   **P0:** A previously authorized employee can reopen the app offline,
    subject to local session policy.
-   **P0:** Store only the minimum protected local credential/session
    material needed for offline access; use Android Keystore-backed
    protection where applicable.
-   **P0:** Offline unlock must not allow an expired/revoked account to
    perform server operations once online.
-   **P1:** Optional device registration and administrator-controlled
    device revocation.
-   **P1:** Audit account creation, disablement, credential resets, and
    privileged actions.

### 5.2 Inspection and property data

-   **P0:** Create, save, resume, and edit inspections.
-   **P0:** Store client name, phone, property address, inspection date,
    assigned inspector, and report status.
-   **P0:** Generate stable IDs on-device so records can be created
    offline.
-   **P0:** Support draft, in-progress, ready-for-report, finalized, and
    archived states.
-   **P1:** Add optional property type, unit/flat number, access notes,
    and other configurable fields.
-   **P1:** Search and filter historical inspections by date, client,
    address, inspector, and status.

### 5.3 Rooms and areas

-   **P0:** Ship with configurable default rooms/areas, such as living
    room, kitchen, bedroom, bathroom, balcony, and exterior.
-   **P0:** Allow custom rooms/areas, including sit-out, terrace,
    utility, parking, or other site-specific spaces.
-   **P0:** Support renaming and ordering rooms without changing the
    identity of existing findings.
-   **P1:** Allow room templates to be managed centrally and
    synchronized to devices.
-   **P1:** Preserve a room name snapshot in historical reports so later
    template edits do not rewrite old reports.

### 5.4 Findings and notes

-   **P0:** Multiple findings per room and multiple photos per finding.
-   **P0:** Quick rough notes can be saved at the site.
-   **P0:** Detailed notes can be edited later without losing the
    original rough note unless explicitly removed.
-   **P0:** Include optional title, category, severity, detailed
    observation, implication, recommendation, and photo captions.
-   **P0:** Auto-save edits locally; show save state.
-   **P1:** Configurable severity levels (for example, informational,
    minor, moderate, major, urgent).
-   **P1:** Track creation/update timestamps and the responsible user.
-   **P1:** Allow a finding to be marked not applicable or resolved
    where the report process requires it.

### 5.5 Photo capture and storage

-   **P0:** Capture through the native Android camera pipeline (CameraX
    or a validated equivalent).
-   **P0:** Request the highest practical quality supported by the
    device, subject to available storage and camera capability.
-   **P0:** Save the original file locally before considering the photo
    captured successfully.
-   **P0:** Do not block photo capture on network availability.
-   **P0:** Associate each photo with an inspection, room/area, finding,
    and stable photo ID.
-   **P0:** Preserve original bytes; never overwrite the original with a
    thumbnail or PDF-optimized image.
-   **P0:** Store metadata including dimensions, MIME type, file size,
    timestamps, checksum, local path, and sync/backup status.
-   **P0:** Use atomic file-write/finalization patterns and recover
    safely from interrupted writes.
-   **P0:** Keep a persistent upload queue that survives process death
    and device restart.
-   **P0:** Verify uploaded object size and checksum, or equivalent
    server-side integrity evidence, before marking it backed up.
-   **P0:** Display per-photo states: local only, queued, uploading,
    retry required, server verified, and eligible for cleanup.
-   **P0:** Warn about low storage and handle insufficient space without
    falsely confirming success.
-   **P1:** Provide an export/recovery option for selected originals to
    a user-visible folder or approved external storage.
-   **P1:** Generate thumbnails and report variants asynchronously
    on-device.

**Photo success definition:** a photo is "saved locally" only after the
image file is durably finalized and its metadata record is committed. It
is "backed up" only after the server confirms the complete stored object
and integrity verification succeeds.

### 5.6 Offline-first behavior and synchronization

-   **P0:** All core inspection operations work offline after
    authorization.
-   **P0:** The local database is the immediate source of truth for
    current work on that device.
-   **P0:** Use a persistent, resumable, retryable sync queue.
-   **P0:** Sync structured records and image objects independently so a
    large upload does not block note updates.
-   **P0:** Use idempotency keys/stable IDs to prevent duplicate records
    when requests are retried.
-   **P0:** Retry transient failures with exponential backoff and
    jitter, respecting Android background execution and battery limits.
-   **P0:** Resume pending work after connectivity returns, app restart,
    and device restart.
-   **P0:** Show last successful sync time, pending item counts, and
    actionable failures.
-   **P0:** Never label an inspection fully synced while required
    records or photos remain pending.
-   **P0:** Detect record-version conflicts; do not silently overwrite
    another device's newer edit.
-   **P1:** Support manual "Sync now" and diagnostics export.
-   **P1:** Allow configurable upload policies (Wi-Fi only, mobile data
    allowed) with sensible defaults.

### 5.7 PDF generation and sharing

-   **P0:** Generate PDFs entirely on-device, including offline.
-   **P0:** Use local data and locally available report image variants;
    no server processing required.
-   **P0:** Include client/property information, report number, date,
    summary, room-by-room findings, severity, descriptions,
    recommendations, captions, page numbers, and branding.
-   **P0:** Preview the PDF before sharing.
-   **P0:** Regenerate the PDF after report content changes.
-   **P0:** Use Android's native share sheet and a content URI with
    temporary read permission.
-   **P0:** Store the final shared PDF version locally and sync it when
    online if configured.
-   **P0:** Keep report version/status separate from photo backup
    status.
-   **P1:** Add configurable cover page, company logo, disclaimers, and
    signature blocks.
-   **P1:** Provide a lightweight PDF mode and a higher-detail mode if
    testing shows a useful trade-off.

### 5.8 History and central hub

-   **P0:** Authorized employees can view locally cached records
    offline.
-   **P0:** When online, retrieve permitted historical records from the
    central server.
-   **P0:** Server stores shared metadata, inspection records,
    user/permission data, photo objects, report artifacts if required,
    and audit/sync state.
-   **P0:** Server does not generate PDFs, resize images, run AI
    inference, or perform other unnecessary processing.
-   **P1:** Admin dashboard/API views for employee, inspection, backup,
    and sync status.

### 5.9 Retention and cleanup

-   **P0:** Make retention policy configurable; initial proposed policy
    is **30 days after a report is finalized and the server backup is
    verified**.
-   **P0:** Do not start the 30-day clock at photo capture, upload
    start, or draft creation.
-   **P0:** Cleanup eligibility requires all of the following:
    1.  Report is finalized.
    2.  Required inspection metadata is synced.
    3.  Every original photo is verified on the server by checksum or
        equivalent integrity check.
    4.  The retention interval has elapsed.
    5.  No active upload, edit, report regeneration, or recovery
        operation depends on the local original.
    6.  The user has been shown the retention policy and the app records
        the cleanup event.
-   **P0:** Do not automatically delete the only remaining copy.
-   **P0:** If the server backup is unavailable, corrupt, incomplete, or
    not verifiable, keep the local original and show the reason cleanup
    is blocked.
-   **P0:** Before permanent deletion, re-check eligibility at deletion
    time; do not rely only on an old eligibility flag.
-   **P1:** Notify employees before cleanup and provide a configurable
    grace period or administrator override.
-   **P1:** Keep thumbnails or lower-resolution local report assets only
    if permitted by the storage policy and if doing so does not
    misrepresent the existence of the original.
-   **P1:** Log deletion timestamp, photo ID, device, policy version,
    and backup verification reference.

**Important retention distinction:** a verified server copy protects
against ordinary phone loss, but the server should itself have a defined
backup/retention policy. "Backed up" should mean the server has durably
stored and verified the object---not merely received an upload request.

### 5.10 Performance and reliability

-   **P0:** Avoid network calls on the UI thread.
-   **P0:** Use asynchronous I/O for file operations, image variants,
    database operations where appropriate, and uploads.
-   **P0:** Use paginated/lazy lists for inspections and photo
    galleries.
-   **P0:** Load thumbnails in lists; load full-resolution images only
    when needed.
-   **P0:** Avoid decoding multiple full-resolution images into memory
    simultaneously.
-   **P0:** Stream uploads where supported rather than loading whole
    image files into RAM.
-   **P0:** Keep database transactions small and use appropriate
    indexes.
-   **P0:** Avoid unnecessary recomposition and expensive work inside
    Compose UI rendering.
-   **P0:** Bound concurrency for thumbnail generation and uploads to
    control memory, heat, and battery usage.
-   **P0:** Use profiling and measurements rather than assuming an
    optimization is beneficial.
-   **P1:** Establish performance budgets on representative low-, mid-,
    and high-tier Android phones.

------------------------------------------------------------------------

## 6. Proposed Technical Architecture

### 6.1 Android client

**Preferred stack** - Kotlin - Jetpack Compose for UI - CameraX for
photo capture - Room over SQLite for local structured data - WorkManager
for persistent deferred background work - Android Keystore for
protecting local cryptographic material - Kotlin coroutines and Flow for
asynchronous work and reactive UI state - Android PDF APIs or a
carefully selected maintained PDF library for local report generation -
Android Sharesheet with `FileProvider`/content URIs for safe document
sharing

Use current stable versions compatible with the minimum supported
Android version selected during implementation. Pin and update
dependencies deliberately; do not use alpha dependencies for core photo,
storage, or synchronization paths without a strong reason.

### 6.2 Layered code structure

Keep the project modular without overengineering the first release:

-   `app`: application startup, navigation, dependency wiring
-   `core/model`: domain models and shared value types
-   `core/database`: Room entities, DAOs, migrations
-   `core/storage`: original photo files, checksums, atomic writes,
    cleanup
-   `core/network`: API client, authentication, upload/download
-   `core/sync`: outbox queue, retry policy, conflict handling, sync
    state
-   `core/security`: session handling, encrypted local secrets,
    permission checks
-   `feature/auth`
-   `feature/inspections`
-   `feature/findings`
-   `feature/camera`
-   `feature/report`
-   `feature/history`
-   `feature/admin` (only if admin UI belongs in the Android app)
-   `feature/settings`

Use repository interfaces to separate UI/domain logic from storage and
network implementations. Do not create abstractions for every trivial
class; abstractions should support testability, offline behavior, or a
real boundary.

### 6.3 Central server

The existing VPS/cloud server is a central hub, not a processing engine.

Responsibilities: - Authentication and account administration. -
Authorization and record access. - Accept and store inspection
metadata. - Issue upload authorization or accept authenticated
uploads. - Store original images and optional report artifacts. - Verify
object integrity and return durable backup status. - Provide
history/search APIs and sync cursors. - Track record versions, audit
events, and device sync status.

Avoid: - Server-side PDF generation. - Server-side image compression or
thumbnail generation. - AI inference or defect analysis. - Synchronous
heavy processing during API requests.

**Storage recommendation:** use existing object storage if already
available. If only the VPS is available, object storage can be hosted on
it for the initial release, but capacity, disk failure, backup, and
off-site recovery must be explicitly managed. Do not store large image
blobs directly in PostgreSQL unless there is a compelling operational
reason. Store metadata in PostgreSQL and image bytes in object storage
or a filesystem/object-storage service.

### 6.4 High-level data flow

1.  Camera capture produces an original file.
2.  Local storage finalizes the file and calculates a checksum.
3.  A local database transaction records the photo metadata and a
    pending upload item.
4.  A background worker sends the metadata and file when network/battery
    constraints allow.
5.  Server stores the file and verifies its checksum.
6.  Server returns a durable object reference and verification result.
7.  Client records the verified backup state.
8.  Local cleanup becomes eligible only after finalization, backup
    verification, and the retention interval.

The UI must not wait for the server to finish any of these steps before
the employee can continue inspecting.

------------------------------------------------------------------------

## 7. Data Model (Initial Proposal)

Use UUIDs generated on the device for records that may be created
offline. All timestamps should be stored in UTC with the device's
original local timezone retained where it matters for reporting.

### 7.1 `users`

-   `id`
-   `employee_code` (unique)
-   `display_name`
-   `role`
-   `status`
-   `created_at`
-   `updated_at`
-   `disabled_at` (nullable)

Passwords/password hashes are handled by the server authentication
system and must not be included in ordinary user-sync payloads.

### 7.2 `devices`

-   `id`
-   `user_id`
-   `device_label`
-   `registered_at`
-   `last_seen_at`
-   `revoked_at` (nullable)
-   `app_version`
-   `platform_version`

### 7.3 `inspections`

-   `id`
-   `server_id` (nullable until acknowledged)
-   `client_name`
-   `client_phone`
-   `property_address`
-   `property_type` (nullable)
-   `inspector_user_id`
-   `inspection_date`
-   `status`
-   `report_version`
-   `created_at`
-   `updated_at`
-   `finalized_at` (nullable)
-   `server_version` or `revision`
-   `deleted_at` (nullable tombstone; avoid immediate hard deletion)

### 7.4 `rooms`

-   `id`
-   `inspection_id`
-   `name`
-   `sort_order`
-   `is_custom`
-   `created_at`
-   `updated_at`
-   `server_version` or `revision`

### 7.5 `findings`

-   `id`
-   `inspection_id`
-   `room_id`
-   `title`
-   `category`
-   `severity`
-   `rough_note`
-   `detailed_observation`
-   `implication`
-   `recommendation`
-   `status`
-   `created_by`
-   `created_at`
-   `updated_at`
-   `server_version` or `revision`

### 7.6 `photos`

-   `id`
-   `inspection_id`
-   `room_id`
-   `finding_id`
-   `local_original_path`
-   `server_object_key` (nullable until uploaded)
-   `mime_type`
-   `width`
-   `height`
-   `file_size_bytes`
-   `sha256`
-   `captured_at`
-   `caption`
-   `upload_state`
-   `backup_verified_at` (nullable)
-   `backup_checksum` (nullable)
-   `cleanup_eligible_at` (nullable)
-   `original_deleted_at` (nullable)
-   `created_at`
-   `updated_at`

### 7.7 `sync_outbox`

-   `id`
-   `entity_type`
-   `entity_id`
-   `operation`
-   `idempotency_key`
-   `payload_version`
-   `attempt_count`
-   `next_attempt_at`
-   `last_error_code` (safe, non-secret)
-   `created_at`
-   `updated_at`

### 7.8 `reports`

-   `id`
-   `inspection_id`
-   `version`
-   `local_pdf_path`
-   `server_object_key` (nullable)
-   `content_hash`
-   `generated_at`
-   `finalized_at` (nullable)
-   `shared_at` (nullable)
-   `backup_verified_at` (nullable)

### 7.9 `audit_events`

Record security-sensitive and retention-related events, including
account changes, finalization, backup verification, cleanup, and
administrative overrides. Avoid storing passwords, access tokens, or
unnecessary client-sensitive content in logs.

**Schema rule:** local database migrations must preserve data. Never use
destructive schema migration behavior in production.

------------------------------------------------------------------------

## 8. Synchronization Protocol

### 8.1 Principles

-   Local-first writes.
-   Stable client-generated IDs.
-   Idempotent APIs.
-   Explicit revisions/version numbers.
-   Durable local outbox.
-   Separate metadata and file upload status.
-   Retry-safe uploads.
-   Server-verified photo backup.
-   Conflict detection instead of silent last-write-wins overwrites.

### 8.2 Suggested sync sequence

1.  Client requests a sync cursor or submits pending metadata.
2.  Server acknowledges each record or returns a conflict/error state.
3.  Client requests an upload authorization or begins an authenticated
    upload.
4.  File uploads using a streaming/multipart or resumable method
    suitable for expected image sizes.
5.  Server verifies byte count and checksum.
6.  Server returns an object key, checksum, and verification timestamp.
7.  Client commits the verified state locally.
8.  Client fetches changes since its last successful cursor.
9.  Client reconciles remote changes and flags conflicts for review.

### 8.3 Retry behavior

-   Retry transient network and server failures with exponential backoff
    and jitter.
-   Do not retry permanent validation/authentication errors
    indefinitely.
-   Pause uploads when the user has disabled data usage or the device is
    under relevant constraints.
-   Keep all queue state in durable storage; WorkManager is a scheduler,
    not the sole source of queue truth.
-   Use bounded concurrency to protect battery, memory, and network
    responsiveness.

### 8.4 Conflict policy

For the first release, avoid simultaneous editing of the same inspection
by multiple devices. Use revision numbers/ETags. If a stale client
attempts to overwrite a newer revision, return a conflict and preserve
the local draft. Offer a clear resolution path rather than silently
discarding either version.

------------------------------------------------------------------------

## 9. API Surface (Draft)

Final endpoint names depend on the existing server framework, but the
logical API should cover:

-   `POST /auth/login`
-   `POST /auth/refresh`
-   `POST /auth/logout`
-   `GET /me`
-   `GET /sync/changes?cursor=...`
-   `POST /sync/inspections`
-   `POST /sync/rooms`
-   `POST /sync/findings`
-   `POST /photos/upload-intents` (or equivalent authenticated upload
    initiation)
-   `POST /photos/{id}/complete` (server verifies object integrity)
-   `GET /inspections`
-   `GET /inspections/{id}`
-   `GET /photos/{id}/status`
-   `POST /reports/metadata`
-   `POST /inspections/{id}/finalize`
-   Admin endpoints for users, roles, templates, retention settings, and
    audit views.

If the VPS already has a backend/API, adapt these capabilities to the
existing routes and authentication rather than building duplicate
services. All endpoints require TLS. Enforce authorization and validate
every request server-side.

------------------------------------------------------------------------

## 10. Offline Login and Security

Offline login cannot check live account revocation, so the product must
make that limitation explicit.

Recommended behavior: - First login and device authorization require a
connection. - Store a protected local authorization record tied to the
employee and device. - Use a device-bound key protected by Android
Keystore to protect local session material. - Permit offline access to
locally available records while the local authorization remains valid
under the chosen policy. - On reconnect, refresh authorization and apply
account/device revocations. - If an account was disabled while the
device was offline, the app cannot know until reconnecting; limit
sensitive actions according to the offline policy. - Provide a local app
lock/PIN or biometric unlock as a convenience layer, not as a
replacement for server authentication. - Never store a reusable
plaintext password. - Use TLS for all network traffic, secure token
handling, least-privilege API access, and safe log redaction. - Consider
encrypting particularly sensitive local data and files based on threat
model and device fleet constraints. - Prevent accidental disclosure
through exported logs, thumbnails, notifications, or shared temporary
files.

The business must choose an offline authorization expiry period. A
shorter period improves revocation control but can prevent work during
prolonged outages; a longer period improves field availability but
leaves revoked access usable until the device reconnects or its local
authorization expires.

------------------------------------------------------------------------

## 11. Image and PDF Optimization

### 11.1 Preserve originals

Original photos are evidence and must remain immutable. Store
report-specific derivatives separately.

### 11.2 Variants

-   **Original:** captured image at the best practical supported
    quality.
-   **Thumbnail:** small image for lists and room summaries.
-   **Report image:** resized/compressed copy for PDF embedding.
-   **Optional preview:** medium-size image for detail views where full
    resolution is unnecessary.

### 11.3 Initial PDF image settings to validate

-   Longest edge around 1,600--2,000 pixels as an initial test.
-   JPEG quality around 80% as an initial test.
-   Preserve image orientation and avoid stripping useful capture
    metadata from the original.
-   Test fine cracks, dampness, hairline gaps, and other small defects
    before setting production defaults.
-   Use per-report limits and page layout rules to avoid extreme PDF
    size.

These values are starting points, not universal constants. Tune them
against actual images and report readability.

### 11.4 Memory efficiency

-   Decode thumbnails at target dimensions instead of decoding originals
    and shrinking them in memory.
-   Process one bounded batch at a time.
-   Release bitmap/native image resources promptly.
-   Stream file reads and uploads where possible.
-   Avoid keeping all report images in memory while generating a
    multi-page PDF.
-   Cache derivatives with versioned keys so changed report settings do
    not reuse stale outputs.

------------------------------------------------------------------------

## 12. Local Retention Policy: Proposed 30-Day Cleanup

**Proposed default:** permanently remove a local original 30 days after
the inspection report is finalized, only if a verified server copy
exists and all eligibility checks pass.

This is a policy proposal, not permission to delete unconditionally.

### Cleanup algorithm

1.  Report is finalized.
2.  All required inspection records are synchronized.
3.  Each original image is confirmed present on the server.
4.  The server's stored checksum matches the local original checksum.
5.  Set `cleanup_eligible_at` to the applicable policy date (initially
    finalization time plus 30 days, provided all verification conditions
    are met).
6.  Before deletion, revalidate finalization, backup status, checksum
    evidence, current retention configuration, and absence of pending
    operations.
7.  Notify the employee and/or admin according to configured policy.
8.  Delete the local original safely.
9.  Mark the photo as `original_deleted_at` and retain metadata showing
    that the original was removed from the device.
10. If any check fails, do not delete; show the reason and retry
    eligibility checks later.

### Important product decisions

-   Decide whether 30 days begins at finalization or at the later of
    finalization and backup verification. The safer default is the later
    of those two timestamps plus 30 days.
-   Decide whether a local copy of the final PDF should be retained
    after original cleanup.
-   Decide whether employees can pin/keep specific inspections locally.
-   Ensure server-side backups and disaster recovery exist before
    relying on server-only originals.
-   Explain clearly that permanent local deletion cannot be undone from
    the device if the server copy later becomes unavailable.

No retention job should run destructive operations based only on a date.
Every deletion must re-check the complete safety predicate.

------------------------------------------------------------------------

## 13. PDF Report Specification

### Suggested report structure

1.  Cover/header with company branding and report ID.
2.  Client and property details.
3.  Inspection date, inspector, and report version.
4.  Summary of significant findings.
5.  Room/area sections.
6.  Finding title, severity, detailed observation, implication, and
    recommendation.
7.  One or more compressed images per finding, each with a caption.
8.  Scope/limitations and general notes.
9.  Footer with page number, report ID, and version.

### Report versioning

-   Draft PDF may be regenerated as many times as needed.
-   Finalization creates a numbered report version.
-   A changed finalized report should create a new version, not silently
    replace the shared version.
-   Store the content hash of the final PDF.
-   Record sharing as a local event; do not claim WhatsApp/email
    delivery was confirmed unless an actual delivery integration
    provides that confirmation.
-   Sharing must work without internet if the PDF is available locally.

------------------------------------------------------------------------

## 14. Internal APK Distribution and Updates

-   Build a signed release APK.
-   Keep the signing key secure and backed up under controlled access.
-   Use a consistent application ID and signing identity so updates
    install over the previous version.
-   Increment version code for each release.
-   Provide an internal download page or controlled distribution method.
-   Document Android's "install unknown apps" permission process for
    employees.
-   Verify APK authenticity and release notes.
-   Test upgrades with existing local inspection data before rollout.
-   Never require app reinstallation as a normal update path if it would
    risk local data.
-   Provide a tested export/recovery procedure before major migrations.

------------------------------------------------------------------------

## 15. Performance Targets and Measurement

Set measurable targets after testing on representative devices. Initial
targets:

-   Local save of ordinary text edits should feel immediate; no network
    dependency.
-   Camera capture must not wait for upload or thumbnail generation.
-   The dashboard should render from local data first.
-   Opening a cached inspection should not require a network request.
-   Thumbnail galleries should use lazy loading and avoid
    full-resolution decoding.
-   Background synchronization should not block scrolling, editing, or
    camera use.
-   PDF generation should show progress for large reports and remain
    cancellable where safe.
-   Memory use should remain bounded for reports with many photos.
-   Battery use should be measured during a representative inspection
    and sync session.
-   No data loss in tested process-kill, restart, offline, retry, and
    duplicate-request scenarios.

Use Android Studio Profiler, Macrobenchmark, Microbenchmark where
useful, StrictMode during development, database query inspection, and
structured non-sensitive diagnostics. Optimize from measured
bottlenecks; avoid premature complexity.

------------------------------------------------------------------------

## 16. Testing and Acceptance Criteria

### 16.1 Photo safety

-   Capture photos in airplane mode and confirm local originals can be
    reopened.
-   Kill the app during and immediately after capture; confirm completed
    photos and records remain consistent.
-   Restart the phone with pending uploads; confirm queue resumes.
-   Simulate partial uploads, server errors, timeouts, and lost
    acknowledgments.
-   Verify that retries do not create duplicate objects or records.
-   Compare checksums before and after upload.
-   Simulate low storage and confirm the app never reports an unsaved
    image as saved.

### 16.2 Offline workflow

-   Log in online once, then complete a full inspection offline.
-   Reopen the app offline and resume the inspection.
-   Edit notes, add rooms, generate a PDF, and share it offline.
-   Reconnect and verify all records and photos sync.
-   Disable/revoke an account server-side and verify revocation is
    applied on reconnect.

### 16.3 PDF

-   Verify room ordering, captions, image orientation, pagination, page
    numbers, report version, and branding.
-   Test reports with many photos and very large source images.
-   Confirm that compression does not obscure small defects.
-   Confirm the original image checksum remains unchanged after PDF
    generation.
-   Share through WhatsApp and email on representative Android devices.

### 16.4 Retention

-   Ensure no original is deleted before the retention date.
-   Ensure no original is deleted when the server checksum is missing or
    mismatched.
-   Ensure no original is deleted while an upload or edit is pending.
-   Ensure cleanup re-checks all eligibility conditions immediately
    before deletion.
-   Confirm deletion events are logged and local metadata remains
    understandable.
-   Test the case where a previously verified server object is later
    unavailable; prevent cleanup or raise a critical backup warning
    wherever the client can detect this.

### 16.5 Security and data integrity

-   Verify server-side authorization for every endpoint.
-   Test disabled accounts and revoked devices.
-   Verify no plaintext passwords/tokens appear in logs or local
    database fields.
-   Test schema migrations with real-sized sample inspection data.
-   Test conflicts from stale revisions and ensure neither version
    silently disappears.

### Definition of Done for the first release

The first release is not ready until an employee can complete a
representative inspection entirely offline, restart the app without
losing saved photos or notes, generate and share a readable PDF offline,
reconnect and verify server backups, and demonstrate that the retention
process cannot delete an unverified or sole remaining original.

------------------------------------------------------------------------

## 17. Risks and Mitigations

  -----------------------------------------------------------------------
  Risk                                Mitigation
  ----------------------------------- -----------------------------------
  Phone loss or hardware failure      Immediate local persistence,
  before sync                         prominent pending-sync status,
                                      prompt syncing, optional approved
                                      export/recovery

  Partial or corrupt uploads          Checksums, server verification,
                                      idempotent retry, explicit backup
                                      states

  Accidental local deletion           Retention gates, pre-deletion
                                      revalidation, audit log, server
                                      backup requirement

  Server disk failure                 Independent server backups,
                                      off-site/object-store durability,
                                      recovery drills

  Offline account revocation delay    Clearly defined offline
                                      authorization expiry and
                                      reconnect-time revocation

  Oversized PDFs                      Report-specific derivatives,
                                      bounded image dimensions, quality
                                      testing

  Out-of-memory errors                Thumbnail decoding, bounded
                                      concurrency, streaming, incremental
                                      PDF generation

  Duplicate sync writes               Stable UUIDs and idempotency keys

  Conflicting edits                   Revisions/ETags and explicit
                                      conflict resolution

  APK update breaks local data        Backward-compatible migrations,
                                      signed upgrades, pre-release
                                      upgrade tests

  Client data exposure                Role-based access, secure local
                                      secrets, TLS, limited exports, log
                                      redaction
  -----------------------------------------------------------------------

------------------------------------------------------------------------

## 18. Implementation Plan

### Phase 0 --- Inspect existing infrastructure

-   Document the current VPS, API framework, database, authentication,
    disk capacity, backup policy, and available object storage.
-   Decide the minimum supported Android version and target device
    range.
-   Confirm the report format and company branding requirements.
-   Define offline authorization expiry and retention policy.

**Exit criterion:** infrastructure and product assumptions are
documented; no duplicate backend is built unnecessarily.

### Phase 1 --- Reliability prototype

-   Create the Android project and architecture skeleton.
-   Implement local database and durable original-photo storage.
-   Capture photos, calculate checksums, and link them to a draft
    inspection.
-   Test airplane mode, process death, device restart, and low-storage
    behavior.

**Exit criterion:** local capture and recovery pass photo-safety tests.

### Phase 2 --- Core inspection experience

-   Login and local authorized session.
-   Inspection dashboard and property details.
-   Default/custom rooms, findings, rough notes, detailed notes, and
    captions.
-   Local history and auto-save.

**Exit criterion:** a complete inspection can be created and edited
offline.

### Phase 3 --- PDF and sharing

-   Build report layout and image derivative pipeline.
-   Generate PDFs offline.
-   Preview, version, and share through Android's native share sheet.

**Exit criterion:** representative PDFs remain readable and
appropriately sized, and original photos are unchanged.

### Phase 4 --- Central synchronization

-   Implement API integration, durable outbox, retries, idempotency,
    upload verification, and history fetch.
-   Add conflict handling and sync diagnostics.
-   Validate server storage and backup policies.

**Exit criterion:** offline-created records and original photos sync
reliably and are verifiably backed up.

### Phase 5 --- Admin, retention, and pilot

-   Add account administration and required admin views.
-   Implement 30-day cleanup eligibility and pre-deletion checks.
-   Test signed APK upgrades, pilot with a small team, and resolve
    issues.

**Exit criterion:** security, retention, recovery, and pilot acceptance
tests pass.

------------------------------------------------------------------------

## 19. Decisions Still Required Before Coding

The following can be confirmed during Phase 0; they do not prevent
writing the initial client skeleton, but they affect production
readiness.

1.  **Existing backend details:** framework, database, authentication,
    current API routes, and whether object storage is already available.
2.  **Offline authorization window:** how long an authorized employee
    may continue using the app without reconnecting.
3.  **Local retention policy:** proposed 30 days after both finalization
    and verified backup; confirm whether employees can exempt selected
    inspections.
4.  **Server retention and disaster recovery:** backup frequency,
    independent backup location, restore testing, and expected retention
    of original photos.
5.  **Report design:** logo, company name, disclaimer, severity labels,
    page layout, and preferred report language(s).
6.  **Minimum Android version and device fleet:** determines camera and
    storage compatibility testing.
7.  **Employee access model:** whether employees can see only their own
    inspections or all team inspections.
8.  **Finalization permissions:** whether employees can finalize reports
    themselves or only an admin/reviewer can finalize them.
9.  **Offline data protection:** whether device-level protection is
    sufficient or stronger local encryption is required.
10. **PDF retention:** whether the final PDF stays on the phone after
    original-photo cleanup.

------------------------------------------------------------------------

## 20. Final Architecture Recommendation

Build a **native Kotlin Android application with an offline-first local
database and file store**. Use CameraX for capture, Room for structured
local data, WorkManager for persistent sync scheduling, and on-device
PDF generation. Keep original photos immutable, generate thumbnails and
PDF-specific compressed derivatives locally, and use a durable
idempotent synchronization protocol to copy data to the existing central
server.

The server should primarily authenticate, authorize, store, verify, and
synchronize data. It should not perform CPU-intensive report or image
processing. Make local photo cleanup a guarded lifecycle operation, not
a simple timer: the proposed 30-day period begins only after
finalization and verified backup, with every condition checked again
before deletion.

**Non-negotiable engineering rule:** optimize for correctness,
recoverability, and measured performance---not code brevity. A small,
well-layered architecture with clear storage boundaries, explicit state
transitions, reliable tests, and no unnecessary server work will be
faster to maintain and safer than either a monolith full of hidden side
effects or an overengineered collection of microservices.
