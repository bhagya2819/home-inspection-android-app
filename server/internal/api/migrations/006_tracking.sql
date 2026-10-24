-- Live location (feature 2): points recorded while an engineer shares their location, and the
-- sharing events (start, stop, turned off with a reason, interrupted, location switched off). Additive only.
-- Independent of attendance: check-in starts sharing on the phone, but nothing here references it.

CREATE TABLE location_points (
    id          uuid PRIMARY KEY,                -- client-generated; a retried batch is a no-op
    user_id     uuid NOT NULL REFERENCES users (id),
    device_id   uuid REFERENCES devices (id),
    client_at   bigint NOT NULL,                 -- fix time, epoch ms
    lat         double precision NOT NULL,
    lng         double precision NOT NULL,
    accuracy_m  real,
    battery     smallint,                        -- percent at the fix, NULL = unknown
    is_mock     boolean NOT NULL DEFAULT false,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX location_points_user_time ON location_points (user_id, client_at);
CREATE INDEX location_points_age ON location_points (received_at);

CREATE TABLE tracking_events (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id),
    device_id   uuid REFERENCES devices (id),
    kind        text NOT NULL CHECK (kind IN ('START', 'STOP', 'PAUSE', 'INTERRUPTED', 'LOCATION_OFF', 'LOCATION_ON', 'MOCK', 'CONSENT')),
    source      text,                            -- START: CHECK_IN | MANUAL | RESUME | RESTORE; STOP: CHECK_OUT
    reason      text,                            -- PAUSE: LOW_BATTERY | BREAK | OTHER; INTERRUPTED: REBOOT | KILLED | PERMISSION_REMOVED
    note        text,
    client_at   bigint NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tracking_events_user_time ON tracking_events (user_id, client_at);
CREATE INDEX tracking_events_age ON tracking_events (received_at);

-- Admin asked to watch an engineer live; the phone learns it at its next upload.
CREATE TABLE tracking_live (
    user_id    uuid PRIMARY KEY REFERENCES users (id),
    until      timestamptz NOT NULL,
    requested_by uuid REFERENCES users (id)
);
