-- Attendance calendar, leave requests and comp-offs (admin features), plus browser sessions for the
-- web dashboard. Additive only.
--
-- Calendar days are local dates in the company's time zone (Asia/Kolkata). Sunday is the weekly
-- off; holidays are added by the admin. A day's colour (worked / leave / missed / off) is derived
-- on request from attendance + leave + holidays; nothing here stores it.

CREATE TABLE holidays (
    day        date PRIMARY KEY,
    name       text NOT NULL,
    created_by uuid REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE leave_requests (
    id            uuid PRIMARY KEY,                  -- client-generated; a retried POST is a no-op
    user_id       uuid NOT NULL REFERENCES users (id),
    device_id     uuid REFERENCES devices (id),
    kind          text NOT NULL CHECK (kind IN ('CASUAL', 'SICK', 'COMP_OFF')),
    start_day     date NOT NULL,
    end_day       date NOT NULL,
    half          text CHECK (half IN ('FIRST', 'SECOND')),  -- half-day leave; only when start_day = end_day
    reason        text NOT NULL DEFAULT '',
    status        text NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid NOT NULL REFERENCES users (id), -- the engineer, or an admin recording leave for them
    decided_at    timestamptz,
    decided_by    uuid REFERENCES users (id),
    decision_note text NOT NULL DEFAULT '',
    cancelled_at  timestamptz,
    CHECK (end_day >= start_day),
    CHECK (half IS NULL OR start_day = end_day)
);
CREATE INDEX leave_user_days ON leave_requests (user_id, start_day);
CREATE INDEX leave_pending ON leave_requests (created_at) WHERE status = 'PENDING';

-- Comp-offs are earned automatically: a check-in on a Sunday or holiday earns one. The admin can
-- add one for a day without a check-in (GRANT) or take back an automatic one (REVOKE).
CREATE TABLE comp_off_overrides (
    user_id    uuid NOT NULL REFERENCES users (id),
    day        date NOT NULL,
    action     text NOT NULL CHECK (action IN ('GRANT', 'REVOKE')),
    note       text NOT NULL DEFAULT '',
    created_by uuid NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, day)
);

-- Web dashboard sessions: same table, cookie instead of bearer token, shorter life, no device.
ALTER TABLE sessions ADD COLUMN web boolean NOT NULL DEFAULT false;
