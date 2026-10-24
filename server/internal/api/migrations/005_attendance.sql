-- Attendance (feature 1): face check-in/out with location. Additive only.
--
-- Face matching runs on the phone (OpenCV YuNet + SFace); the server stores each engineer's
-- approved face embeddings so any of their phones can check offline, plus the evidence (selfie,
-- score, location) the admin reviews. The server never runs a face model.

-- One row per enrolment attempt. An admin approves it; a newer approval replaces the older one.
CREATE TABLE face_enrollments (
    id            uuid PRIMARY KEY,                -- client-generated
    user_id       uuid NOT NULL REFERENCES users (id),
    device_id     uuid REFERENCES devices (id),
    model         text NOT NULL,                   -- e.g. 'sface-2021dec'; embeddings only compare within a model
    embeddings    jsonb NOT NULL,                  -- [[128 floats], ...] one per enrolment shot
    status        text NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REPLACED')),
    photo_sha256  text NOT NULL,                   -- frontal reference photo the admin compares selfies with
    photo_size    bigint NOT NULL,
    photo_key     text,
    photo_verified_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    decided_at    timestamptz,
    decided_by    uuid REFERENCES users (id)
);
CREATE INDEX face_enrollments_user ON face_enrollments (user_id, created_at DESC);
CREATE UNIQUE INDEX face_enrollments_one_approved ON face_enrollments (user_id) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX face_enrollments_one_pending ON face_enrollments (user_id) WHERE status = 'PENDING';

CREATE TABLE attendance (
    id                uuid PRIMARY KEY,            -- client-generated; a retried POST is a no-op
    user_id           uuid NOT NULL REFERENCES users (id),
    device_id         uuid REFERENCES devices (id),
    kind              text NOT NULL CHECK (kind IN ('IN', 'OUT')),
    client_at         bigint NOT NULL,             -- phone clock, epoch ms
    time_zone         text NOT NULL,
    received_at       timestamptz NOT NULL DEFAULT now(),
    -- Server's own estimate of when it happened, from the phone's monotonic clock (elapsed since
    -- boot) at the event and at upload. NULL when the phone rebooted in between (can't tell).
    estimated_at      timestamptz,
    lat               double precision,            -- NULL = no fix in time
    lng               double precision,
    accuracy_m        real,
    location_at       bigint,
    is_mock           boolean NOT NULL DEFAULT false,
    face_result       text NOT NULL CHECK (face_result IN ('MATCH', 'WEAK', 'NO_MATCH', 'NOT_ENROLLED', 'UNAVAILABLE')),
    face_score        real,
    enrollment_id     uuid REFERENCES face_enrollments (id),
    liveness_passed   boolean NOT NULL DEFAULT false,
    selfie_sha256     text NOT NULL,
    selfie_size       bigint NOT NULL,
    selfie_key        text,
    selfie_verified_at timestamptz,
    selfie_deleted_at timestamptz                   -- set by the 90-day selfie cleanup; the row stays
);
CREATE INDEX attendance_user_time ON attendance (user_id, client_at);
CREATE INDEX attendance_time ON attendance (client_at);
CREATE INDEX attendance_selfie_age ON attendance (received_at) WHERE selfie_key IS NOT NULL AND selfie_deleted_at IS NULL;
