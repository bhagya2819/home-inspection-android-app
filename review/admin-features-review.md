# Review: admin features (calendar, leave, comp-offs, web dashboard)

Branch `claude/admin-attendance-calendar-9c8dda`, commits `1c7f277..1dde018` (4 commits, ~6.4k lines).
Review only: I read the code and ran no builds or tests. Nothing on the phone or the server was touched.
Date: 2026-10-21.

## Summary

The code is careful in the places that usually go wrong:
- All web text goes through `textContent`. The CSP has no `unsafe-inline`.
- The cookie is SameSite=Strict, and changing requests must carry a custom header.
- A web session can only use the routes in an allowlist (`webRoutes`).
- Leave inserts are serialised per user (`SELECT … FOR UPDATE`), and retried POSTs are idempotent.
- The Word export streams photos one at a time, checks the pixel count before decoding, and caches by fingerprint.
- `MIGRATION_6_7` only adds a table, and it has a test.

I found **no Critical issues**, and nothing breaks a CLAUDE.md invariant.

The real gaps are in the **business rules of the calendar**, not in its code paths:

1. **Who is tracked, and since when.** Today that is decided from the account's creation date plus the rule
   "inspector, or has ever checked in". Disabled people disappear from past months. An admin or test account
   that checked in once is yellow for ever after.
2. **A shift that crosses midnight** shows as two yellow days with 0 hours.
3. **The calendar trusts the phone's clock** (`client_at`). The server's own time estimate (`estimated_at`)
   already exists and is not used. This can also earn a comp-off by setting the phone's date to a Sunday.
4. **Leave and work on the same day are not reconciled.** A day can count as both worked and on leave. A half
   day plus a check-in shows plain green. The admin approves a comp-off without seeing the balance.
5. **The Word export is built from live server data, not from a snapshot.** It can quietly differ from the
   PDF, and it disappears as soon as the inspection is reopened.

---

## High

### H1. Calendar days are split by the phone's clock, and a shift crossing midnight breaks
`server/internal/api/calendar.go:109-127` (events bucketed per `localDay(client_at)`), `calendar.go:197-216` (`pairEvents` per day)

- **Midnight scenario.** An engineer checks in at 23:30 on Saturday the 7th after a late inspection, and checks
  out at 00:40 on Sunday.
  - The 7th has an IN with no OUT, so it is `MISSED_CHECKOUT` (yellow) with `worked_ms = 0`.
  - On the 8th the lone OUT is ignored, so the day has no check-in. It is a Sunday, so it shows grey and
    **earns no comp-off**, although they worked on it. On a weekday it would be `MISSED`, a second yellow.
  - The 70 minutes worked appear nowhere.
- **Clock scenario.** The phone's clock is wrong (manual time, dead RTC after a reboot, or set back on purpose).
  The calendar puts the check-in on the day the phone claims. `attendance.estimated_at` (005, the server's
  estimate from the phone's monotonic clock) exists for exactly this, but the calendar ignores it. An engineer
  who sets the phone's date to last Sunday and checks in gets a ★ comp-off. The admin cannot see that the times
  were from a skewed clock.
- **Fix:**
  - Use `COALESCE(estimated_at_ms, client_at)` as the event time.
  - Return a `clock_skew` flag on the day when the two differ by more than about 5 minutes, and show it in the
    day panel.
  - Load events from `from − 1 day` to `to + 1 day` and pair them per user across the whole stream, not per day.
  - Credit an interval to the day of its IN, or split it at midnight. Splitting is more honest for comp-offs.
  - Let an OUT up to about 16 h after an open IN close it, even on the next day.
  - Add table tests for both cases (see M9).

### H2. "Who is on the calendar, and from which day" is implicit and wrong in several cases
`calendar.go:239` (`joined` = `users.created_at`), `calendar.go:282-287`, `calendar.go:446-451` (`usersForCalendar`)

| Case | What happens now |
|---|---|
| Account created before attendance existed (the feature went live 2026-10-21), or created ahead of the joining date | Every working day from `created_at` until real use is `MISSED`. The month totals ("missed 14") are wrong for the first month. Today the effect is small, because accounts were created around 2026-10-20, but it happens for every future hire whose account is set up early. |
| Employee joins mid-month | Correct only if the account was created on the joining day. |
| Employee disabled on the 15th | They disappear from the **team** month entirely (`u.status = 'ACTIVE'`). Their worked days and leave vanish from October, and also from every past month, so the history of a person who left is gone from the admin's main picture. The per-person view still works, but only through the API: neither the web nor the app links to them any more. And `users.disabled_at` (001) is never used, so days after leaving would show `MISSED`. |
| Admin who checks in once (testing face check-in), or the debug `TEST` employee | They match "has ever checked in", so they are listed with every later weekday yellow. An admin who never checks in is hidden. That is right, but only by accident. |

- **Recommendation (the "Track attendance" switch the user was offered): do it.** Use one switch plus one date
  instead of heuristics.
  - In migration 008, add `users.track_attendance boolean NOT NULL DEFAULT (role = 'INSPECTOR')`, filled
    `true` for inspectors and `false` for admins.
  - Add `users.attendance_from date`, defaulting to the later of the go-live date and `created_at`, and editable
    by the admin as the joining date.
  - Team list = `track_attendance AND (status = 'ACTIVE' OR disabled_at >= month_start)`.
  - Days before `attendance_from` or after `disabled_at` are `NONE`.
  - Trade-off: the admin has one more toggle in Employees. Because the default follows the role, they rarely
    touch it. In return the calendar never guesses: the test account and an admin's trial check-in stop
    counting, and an admin who really does field work can be switched on.

### H3. Comp-offs can be earned by accident or by clock games, and the admin isn't told
`calendar.go:290-301`, `calendar.go:338-372`

Any IN on a Sunday or holiday earns a comp-off. That includes a 2-minute check-in, a check-in made only to
test face recognition, the clock trick in H1, and a holiday added *afterwards* for a day someone already worked
(a new ★ appears months later).

No rules is the user's decision, and the ★ is visible, so the admin *can* take it back. But nothing draws
their attention to new ones.

- **Fix, without adding rules:**
  - Show "new comp-offs earned this month" with the day and hours on the team view.
  - Mark comp-offs earned with under 1 h worked, or with a skewed clock, as "check this".
  - Keep earning automatic, as the user decided.

---

## Medium

### M1. Work on a leave day is counted twice and hidden
`calendar.go:267-269`, `calendar.go:303-320`; `leave.go:153-159`, `leave.go:375-385`

- **Scenario:** casual leave for Tuesday is approved, and the engineer then does an urgent inspection and checks
  in on Tuesday.
  - The status is `LEAVE` (red ring), because full leave is checked before worked.
  - But `DaysWorked++`, `WorkedMs += …` and `LeaveDays += 1` all happen, so the month says 1 day worked and
    1 day of leave for the same day.
- **Second scenario:** the engineer asks for sick leave for yesterday (back-dating is allowed for 60 days),
  although they checked in yesterday. The approval dialog shows nothing about that.
- **Fix:**
  - Add a status such as `WORKED_ON_LEAVE`: green with a red ring, counted as worked and not as leave.
  - In `adminLeave`, return `worked_days` per request (days in the range with a check-in) and warn on approval:
    "Checked in on 1 of these days".

### M2. Half days lose information
`calendar.go:268-277`

- **Half-day leave plus a check-in** (the expected case: morning off, afternoon at work) shows plain `WORKED`
  green. The half-red mark the user specified never appears, but the totals still count 0.5 leave.
- **Half-day leave with no check-in** for the other half shows `HALF_LEAVE` and is not counted as missed.
- **Half-day leave with a check-in but no check-out** shows `MISSED_CHECKOUT` and hides the half day.
- **Fix:** keep the leave half visible regardless of the work half. For example, `HALF_LEAVE` plus a separate
  `work` field (`WORKED` / `MISSED` / `MISSED_CHECKOUT`), drawn as half red + half green or yellow. The phone's
  `DayMark` and the web tile CSS both need one more variant.

### M3. Comp-offs are approved blind; a negative balance is visible only on the person screen
`leave.go:301-338` (balance only when `?user=` is given), `LeaveScreens.kt:213-280`, `web/app.js:335-354`

- **Scenario:** an engineer with 0 comp-offs asks for a comp-off day. The admin's leave list (app and web) shows
  "Comp-off · Mon 3 Nov · 1 day" with no balance. On approval the balance silently becomes −1.
- No rules is the user's decision, but the brief asks for the balance to be *at least visible*. Today it is
  visible to the engineer (the Leave screen) and on the admin's per-person calendar, **not where the decision
  is made**.
- **Fix:** for `COMP_OFF` rows, include the requester's `comp_off` balance in `adminLeave`, and show
  "Balance 0 → −1 after approval" in the list and the approve dialog. This informs the admin and does not
  block anything.

### M4. The Word export is built from live server data, latest version only
`docx.go:101-209`, `web.go:216`, `web.go:257`

What this means in practice:

1. **No Word copy at all while an inspection is reopened.** Editing a finalized inspection moves it to
   `IN_PROGRESS` (a CLAUDE.md decision). From then until the next finalize, v1's PDF downloads but there is
   no Word copy of any version, even though v1 was delivered to the client.
2. **The Word copy can differ from the PDF without anyone noticing.** The PDF is made from the phone's data.
   The Word copy is made from what reached the server. If a finding's outbox item failed or is in conflict
   (`Backup & account` shows it, but the web doesn't), the inspection can be `FINALIZED` on the server with
   that finding missing or out of date. The Word copy then omits it while the PDF has it. This is a
   client-facing document.
3. **Photos the admin archived ("Remove from server")** become "(photo moved to the offline archive)". That is
   honest, but a Word copy of an old report is then mostly empty.
4. The cache fingerprint doesn't cover the inspector's display name or a layout change (only a hard-coded
   `v1|` prefix), so a stale file can be served for up to 7 days. This is minor.

**Options:**

- **(a) Recommended: the phone uploads a content snapshot with each finalized version.** At finalize the
  phone writes a small `report-content.json` with exactly the data the PDF used, including photo ids and SHA
  values, and uploads it next to the PDF. The server builds the Word copy from that snapshot.
  - Every version gets a Word copy, and it always matches its PDF.
  - Cost: one more upload type, which fits the existing verified-upload path.
- **(b) The server snapshots its own data when the report is verified.** This needs no phone change, but it
  inherits problem 2 (the server's data may be incomplete at that moment).
- **(c) Keep it as it is, but refuse the Word export** while the inspection has unresolved failed outbox items
  (the server knows them from `devices.sync_report`). At least also say on the web *why* Word is unavailable.

I'd ask the user to choose between (a) and (c). (a) is the real fix.

### M5. Engineers never learn that their leave was decided
`LeaveScreens.kt:387-411` (pull only), `ProfileScreen.kt` (the admin's pending badge only)

- **Scenario:** an engineer asks on Monday for Friday off. The admin rejects it on Wednesday with a note. The
  engineer finds out only by opening Profile → Leave, possibly on Friday.
- **Fix:** keep it in-app, matching the decision of in-app alerts first:
  - During the existing sync pull, fetch `GET /v1/leave` or a `decided_since` cursor.
  - Show a badge on Profile → Leave and a line on the dashboard ("Leave approved: Fri 24 Oct").
  - Firebase push can come later.

### M6. Web dashboard: slow-network races and a search box that loses focus
`web/app.js:91-109`, `web/app.js:145`

- **Race.** `render()` starts an async view and replaces `main` with whatever finishes. On the slow link from
  India, a quick ‹ ‹ on the month bar, or a tab switch during a load, can draw an older response after a newer
  one. October's tiles can then appear under the "September" label: `monthBar` reads `state.month` after the
  `await`.
  - **Fix:** add a render counter (`const seq = ++renderSeq;` … `if (seq !== renderSeq) return;`), or an
    `AbortController` per render.
- **Search focus.** The search box re-renders the whole view 400 ms after you stop typing. The `<input>` is
  rebuilt, so focus and the caret are lost mid-word.
  - **Fix:** update only the table body, or restore focus and the selection after the render.
- **Fix (smaller):** show the number of waiting leave requests on the "Leave" tab, and show pending requests on
  the team strip. The phone shows "asked" in the cell; the strip shows nothing.

### M7. A leave request queued offline waits for the hourly job
`LeaveRepository.kt:144-194`, `SyncEngine.kt:106` (`nextDueAt` only looks at `sync_outbox`), `SyncScheduler.kt:34`

- **Scenario:** an engineer requests leave with no signal, and then the network comes back. `scheduleRetry()`
  sees no due outbox item, so no retry job is scheduled. The request leaves on the hourly periodic job, or the
  next time the app is opened, so up to about 1 h later. The attendance and tracking queues have the same gap.
- **Fix:** let `nextDueAt()` take the minimum over the outbox and the three extra queues: attendance, tracking
  and leave. Each needs a `nextDueAt` DAO query.

### M8. The report list is cut off at 500 inspections, with no paging
`web.go:154-163`

At about 3 inspectors × 3 inspections a day, the cap is reached in about 6 months. Older reports can then be
reached only by searching, and the page never says that the list is truncated.
- **Fix:** add a `before=` cursor (inspection_date, created_at) with "Load more", or at least a
  "showing the newest 500" note.

### M9. The day rules have no tests
`calendar_test.go` (one end-to-end test)

`buildDays` is a pure function. It is the cheapest place to pin down the rules above: midnight shifts, half day
+ work, work on a leave day, holiday added later, disabled and joining dates, REVOKE/GRANT/CLEAR. Add a table
test before changing any of them. It runs on the server with the rest (`sh test.sh`).

---

## Low

- **L1. Leave requests that cover 0 days are accepted.** (`leave.go:42-69`) A request only for a Sunday or a
  holiday validates, waits for the admin as "0 days", and does nothing. **Fix:** refuse it with "these days are
  already off", or warn the user. The comment at `leave.go:454` says "the leave form greys them out", but the
  phone's `LeaveFormDialog` never loads holidays.
- **L2. An engineer's pending request blocks the admin from recording leave, with the wrong message.**
  (`leave.go:184-187`, `leave.go:206`) The admin gets "you already have leave on one of these days". The
  comment at `leave.go:376` ("two pending requests may overlap") is stale, because inserts refuse an overlap
  with pending requests too. **Fix:** word the message for the admin ("this engineer has a pending request:
  approve it instead"), and drop the comment.
- **L3. "Take back" is stored as `REJECTED` and cannot be undone.** (`leave.go:372`) A mis-tap on Reject, or
  taking leave back, is final. Only rejected → approved is missing. **Fix:** allow `REJECTED → APPROVED` within
  a few days, or store `REVOKED` separately so the engineer sees "taken back" and not "rejected".
- **L4. An admin can approve their own leave request.** This is harmless with one owner-admin, but it is worth
  one line in the audit, or a refusal once there are several admins.
- **L5. Holidays change history retroactively, and nobody is told.** Adding a holiday after leave was approved
  removes that day from the leave, and from the comp-off "used" count. That is correct, and automatic. Removing
  a holiday after its comp-off was used makes the balance negative. Both are audited, but the UI shows no
  trace. **Fix:** when a holiday is added or removed and it changes anyone's comp-off or leave days, say so in
  the response and the toast ("affects 2 engineers"). Also, `putHoliday` accepts any date (year 0001); limit it
  to 2020–2100, as `listHolidays` already does.
- **L6. Changing the password on the web signs the phone out.** (`auth.go:500`, existing behaviour) The phone's
  7-day offline verifier keeps the *old* password until its next online sign-in. Offline, the inspector must
  then type the old password. This is not new, but the web makes it more likely. Mention it in the web's
  "password changed" message.
- **L7. Missing hardening headers.** There is no HSTS header from the app (check that Caddy sends one), and the
  cookie could use the `__Host-` prefix (`__Host-griha_web`, which already has Path=/ and Secure and no
  Domain). Both are cheap.
- **L8. Small Android leave items.**
  - `LeaveRepository.request` rethrows auth and password errors after the row is saved, so the UI shows an
    error for a request that is in fact queued (`LeaveRepository.kt:153-159`).
  - Rows that were delivered (`sent_at`) are never deleted. They are tiny, but they could be pruned after
    30 days, like the location points are.
- **L9. Times on the phone use the phone's time zone.** `clockTime` and `YearMonth.now()` in `LeaveScreens.kt`
  use the phone's zone, while the web uses Asia/Kolkata and the server uses +05:30. This only matters on a
  phone set to another zone. Use `ZoneOffset.ofHoursMinutes(5, 30)` for consistency.
- **L10. The CLAUDE.md wording doesn't match the code.** It says "yellow = missed check-in AND missed
  check-out". The code (correctly, per the brief) has two yellows: a circle for a missed check-in and a square
  for a missed check-out. Change the doc line so a future session doesn't "fix" the code.

---

## Business-logic questions, answered

| Question | Current behaviour | Where it goes wrong |
|---|---|---|
| Time zone, day boundaries | Fixed +05:30 on the server, which is right for India (no DST). Days are split by the phone-clock `client_at`. | A shift from 23:30 to 00:40 gives two yellow days and 0 h. A wrong phone clock gives the wrong day, or a comp-off (H1). |
| Account creation date | Days before `created_at` are `NONE`; all days after are `MISSED`. | An account created before rollout or before the joining date is all yellow (H2). |
| Joining mid-month | Correct only if the account was created on the joining day. | Same as above; add `attendance_from`. |
| Disabled employees | Hidden from the team month, including past months; `disabled_at` is ignored. | A person who left vanishes from history (H2). |
| Admins who check in | Listed if they ever checked in. | One trial check-in makes them yellow for ever. **Do the Track-attendance switch** (H2). |
| Leave overlapping a check-in | Not checked; LEAVE wins the colour, but both totals count. | The day counts as worked and as leave (M1). |
| Leave on a Sunday or holiday | Counts as 0 and is hidden on that day; a 0-day request is accepted. | A useless pending request (L1). |
| Half day + check-in | Shows as WORKED; the half leave is invisible but counted. | M2. |
| Approving after the date passed | Allowed, with no warning. | The admin approves sick leave for a day the engineer worked (M1). |
| Cancelling approved leave | The engineer can cancel only before it starts; the admin can "take back" (stored as REJECTED). | It can't be undone, and the engineer reads "rejected" (L3). |
| Holiday added after approval | Recomputed on request: the leave day and comp-off use drop automatically. | Correct, but silent; removing a holiday can make a balance negative (L5). |
| Comp-off without an earned one | Allowed; the balance goes negative; it is visible to the engineer and on the admin's per-person page. | **Not visible where the admin approves** (M3). |
| Word export, latest version only | Built from live server data; only for `FINALIZED` with `current == version`. | No Word copy while reopened; it can differ from the PDF; archived photos become placeholders (M4). |
| Web session scope | Allowlist plus cookie-only sessions (`s.web = $3`); phone routes refuse a web cookie. | No hole found. |
| Rate limits | The web login shares the phone's login gate and lockouts; the web is never a "known phone", so the stricter limit applies. | Fine. The Word builds are serialised (1 at a time); an employee can queue builds but not run them in parallel. Acceptable. |
| Logging of personal data | Request logs leave out query strings (so `?q=client name` isn't logged); leave reasons aren't in the audit. | Fine. Leave reasons, which may be medical, are visible only to admins. |
| Inspector doing admin things | Every admin route is `s.admin`; employees on the web get reports only, by decision. | Only an admin approving their own leave (L4). |

---

## What to do next (priority order)

1. **Tracked people and start dates (H2), migration 008.** Add `track_attendance` (default from the role) and
   `attendance_from`, respect `disabled_at`, and put a toggle and joining date in Admin → Employees. This fixes
   the yellow walls before the pilot's first month-end review.
2. **Pairing across midnight and the server's clock (H1).** Use `estimated_at` when present, pair across days,
   and show a clock-skew flag. Write the `buildDays` table tests first (M9).
3. **Reconcile leave with work (M1, M2).** Add the worked-on-leave and half-day-plus-work statuses, and show the
   "checked in on N of these days" warning on approval.
4. **Comp-off visibility where decisions are made (M3, H3).** Show the balance before and after in the
   app/web leave lists, and a "new comp-offs this month" strip with short or skewed ones flagged. This adds no
   rules, as the user decided.
5. **Tell the engineer the decision (M5).** Add an in-app badge and dashboard line from the next sync pull.
6. **Web robustness (M6, M8).** Add a render sequence guard, keep the search focus, add paging or a
   "newest 500" note, and a pending badge on the Leave tab.
7. **Offline queues wake the sync job (M7).** Include attendance, tracking and leave in `nextDueAt()`. This is
   a small change and also helps features 1 and 2.
8. **Ask the user about the Word strategy (M4):** a phone-uploaded content snapshot (recommended) or refusing
   while outbox items have failed. Until then, have the web say *why* Word is unavailable.
9. **Batch of low items (L1–L10)** in one pass, and update CLAUDE.md (L10).
10. **Before release:** run the debug build on the POCO through leave request → approve → calendar, and open a
    generated .docx in real Word (so far it was checked only in QuickLook). Then bump the version. SOS
    (feature 3) is still the next planned feature after this.

---

## Fixes applied (2026-10-21, same branch, not yet deployed or on the phone)

| Finding | Fix |
|---|---|
| H1 midnight / phone clock | `calendar.go` `workByDay`: an IN is closed by the next OUT ≤ 16 h later; time split at midnight (`overnight`); event time = `estimated_at` when present, `clock_skew` when > 5 min off. Comp-off balance uses the same pairing. |
| H2 who / since when | Migration 009: `users.track_attendance` (inspectors on, admins off; new accounts by role) and `attendance_from` (existing: from 2026-10-21); disabled accounts stay in months they worked, `NONE` after `disabled_at`. Admin → Employees: switch + "Tracked from" date. |
| H3 comp-off notice | Team month returns `comp_offs` with `SHORT` (< 1 h) / `CLOCK` checks; listed on the web and the phone. |
| M1 work on leave | `WORKED_ON_LEAVE` (worked, not leave); `worked_days` per request on the admin list and approve dialog. |
| M2 half days | `HALF_LEAVE` + `half_work` (`WORKED`/`MISSED`/`MISSED_CHECKOUT`); the missed half counts as missed; half-and-half marks. |
| M3 comp-off balance | `comp_off` on each comp-off request; "available 0 → −1 after approval (more than earned)". |
| M4 Word copy | Phones write what each finalized PDF showed to `files/report-content/<id>.json` and upload it (`PUT /v1/reports/{id}/content`, stored once); Word for **any** version from it. Older versions: live data, latest only, with a reason on the web. |
| M5 decisions | `LeaveRepository.newDecisions` (after sync, ≤ every 15 min): Home card + Profile badge until Leave is opened. |
| M6 / M8 web | Render sequence guard, search keeps focus, 200-per-page paging with "Load more", Leave tab badge, pending dots on the strip. |
| M7 wake-ups | `SyncEngine.nextDueAt` includes attendance and leave queues and uploaded-PDF content files (location points keep their own rhythm). |
| M9 tests | `TestWorkByDay`, `TestBuildDays` (pure), `TestCalendarUsesServerClockAndTeamRules`, `TestLeaveDecisions`, `TestReportContentSnapshot`; app `LeaveModelsTest`. |
| L1 | `400 NO_WORKING_DAYS`; the phone form counts working days (Sundays + holidays) and the 62-day limit. |
| L2 | Admin gets "pending request: approve it instead"; stale comment removed. |
| L3 | `REVOKED` status; REJECTED/REVOKED → APPROVED ("Approve after all"). |
| L4 | Own leave needs another active admin (`403 SELF_DECISION`), else audited "self-decided". |
| L5 | Holiday PUT/DELETE return `affects`, shown to the admin; dates limited to 2020–2100. |
| L6 | Web says the phone will ask to sign in again after a password change. |
| L7 | `__Host-griha_web` cookie (web users sign in once more after the deploy), HSTS on the dashboard. |
| L8 | Delivered leave rows pruned after 30 days. (`request()` already reported auth errors as "queued": `ApiException` is an `IOException`.) |
| L9 | Phone calendar uses India time for "today", months and clock times. |
| L10 | CLAUDE.md wording aligned. |

Verified: server `go vet` + all tests pass on the server (scratch copy `/root/apps/griha/src-calendar-fixes`, not `src/`),
gofmt clean, govulncheck clean; app `compileDebugKotlin` and `testDebugUnitTest` pass. Not yet: deploy (migration 009),
the phone (debug build), a real Word open of a snapshot-based .docx.
