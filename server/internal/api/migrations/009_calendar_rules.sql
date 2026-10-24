-- Calendar rules from the admin-features review (review/admin-features-review.md), plus the content
-- snapshot behind each report version's Word copy. Additive only; the CHECK change only widens it.

-- Who is on the attendance calendar, and from which day. Admins are off by default (an admin who
-- tried face check-in once must not turn yellow for ever); the admin can switch anyone on or off.
ALTER TABLE users ADD COLUMN track_attendance boolean NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN attendance_from date;
UPDATE users SET track_attendance = (role = 'INSPECTOR');
-- Attendance went live on 2026-10-21: nobody was missing a check-in before it existed.
UPDATE users SET attendance_from = GREATEST((created_at AT TIME ZONE 'Asia/Kolkata')::date, DATE '2026-10-21');
ALTER TABLE users ALTER COLUMN attendance_from SET DEFAULT ((now() AT TIME ZONE 'Asia/Kolkata')::date);
ALTER TABLE users ALTER COLUMN attendance_from SET NOT NULL;

-- Approved leave the admin took back is REVOKED (not REJECTED), so the engineer reads "taken back",
-- and the admin can approve it again after a mis-tap.
ALTER TABLE leave_requests DROP CONSTRAINT leave_requests_status_check;
ALTER TABLE leave_requests ADD CONSTRAINT leave_requests_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'REVOKED'));

-- What a finalized version's PDF showed, uploaded by the phone that made it. The Word copy of any
-- version is built from this, so it always matches its PDF. Immutable once stored.
ALTER TABLE reports ADD COLUMN content_json text;
ALTER TABLE reports ADD COLUMN content_sha256 text;
ALTER TABLE reports ADD COLUMN content_at timestamptz;
