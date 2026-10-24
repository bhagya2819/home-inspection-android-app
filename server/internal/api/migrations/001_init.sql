-- Home Inspection central hub schema (PRD §6.3, §7). Clients (Android today, iOS later) create entity ids,
-- so ids are client UUIDs. Client timestamps are UTC epoch millis, exactly as the device stored them.
-- Every syncable row carries:
--   revision          bumped by the server on each accepted write; clients send it back as base_revision
--   last_mutation_id  "<idempotency_key>:<payload_version>" of the last applied write (retry dedupe)
--   change_seq        global, strictly increasing write order; GET /v1/sync/changes pages by it

CREATE SEQUENCE change_seq;

CREATE TABLE users (
    id                   uuid PRIMARY KEY,
    employee_code        text NOT NULL UNIQUE,
    display_name         text NOT NULL,
    role                 text NOT NULL CHECK (role IN ('ADMIN', 'INSPECTOR')),
    status               text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    password_hash        text NOT NULL,
    must_change_password boolean NOT NULL DEFAULT false,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    disabled_at          timestamptz,
    change_seq           bigint NOT NULL DEFAULT nextval('change_seq')
);

CREATE TABLE devices (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL REFERENCES users (id),
    device_label  text NOT NULL DEFAULT '',
    platform      text NOT NULL DEFAULT '',
    app_version   text NOT NULL DEFAULT '',
    registered_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz
);

-- Opaque bearer tokens; only their SHA-256 is stored.
CREATE TABLE sessions (
    token_hash   bytea PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users (id),
    device_id    uuid REFERENCES devices (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz
);
CREATE INDEX sessions_user ON sessions (user_id);

CREATE TABLE inspections (
    id                uuid PRIMARY KEY,
    client_name       text NOT NULL,
    client_phone      text NOT NULL,
    property_address  text NOT NULL,
    property_type     text,
    unit_number       text,
    access_notes      text,
    inspector_user_id text NOT NULL,
    inspection_date   text NOT NULL,
    time_zone         text NOT NULL,
    status            text NOT NULL,
    report_version    bigint NOT NULL,
    created_at        bigint NOT NULL,
    updated_at        bigint NOT NULL,
    finalized_at      bigint,
    deleted_at        bigint,
    revision          bigint NOT NULL,
    last_mutation_id  text NOT NULL,
    updated_by        uuid NOT NULL,
    server_updated_at timestamptz NOT NULL DEFAULT now(),
    change_seq        bigint NOT NULL
);

CREATE TABLE rooms (
    id                uuid PRIMARY KEY,
    inspection_id     uuid NOT NULL REFERENCES inspections (id),
    name              text NOT NULL,
    sort_order        bigint NOT NULL,
    is_custom         boolean NOT NULL,
    created_at        bigint NOT NULL,
    updated_at        bigint NOT NULL,
    deleted_at        bigint,
    revision          bigint NOT NULL,
    last_mutation_id  text NOT NULL,
    updated_by        uuid NOT NULL,
    server_updated_at timestamptz NOT NULL DEFAULT now(),
    change_seq        bigint NOT NULL
);

CREATE TABLE findings (
    id                   uuid PRIMARY KEY,
    inspection_id        uuid NOT NULL REFERENCES inspections (id),
    room_id              uuid NOT NULL REFERENCES rooms (id),
    title                text,
    category             text,
    severity             text,
    rough_note           text NOT NULL,
    detailed_observation text,
    implication          text,
    recommendation       text,
    status               text NOT NULL,
    sort_order           bigint NOT NULL,
    created_by           text NOT NULL,
    created_at           bigint NOT NULL,
    updated_at           bigint NOT NULL,
    deleted_at           bigint,
    revision             bigint NOT NULL,
    last_mutation_id     text NOT NULL,
    updated_by           uuid NOT NULL,
    server_updated_at    timestamptz NOT NULL DEFAULT now(),
    change_seq           bigint NOT NULL
);

-- Photo bytes live on disk under DATA_DIR (PRD §6.3: not in PostgreSQL).
CREATE TABLE photos (
    id                uuid PRIMARY KEY,
    inspection_id     uuid NOT NULL REFERENCES inspections (id),
    room_id           uuid NOT NULL REFERENCES rooms (id),
    finding_id        uuid NOT NULL REFERENCES findings (id),
    mime_type         text NOT NULL,
    width             bigint NOT NULL,
    height            bigint NOT NULL,
    file_size_bytes   bigint NOT NULL,
    sha256            text NOT NULL,
    captured_at       bigint NOT NULL,
    caption           text,
    created_at        bigint NOT NULL,
    updated_at        bigint NOT NULL,
    deleted_at        bigint,
    object_key        text,
    verified_at       timestamptz,
    revision          bigint NOT NULL,
    last_mutation_id  text NOT NULL,
    updated_by        uuid NOT NULL,
    server_updated_at timestamptz NOT NULL DEFAULT now(),
    change_seq        bigint NOT NULL
);

CREATE TABLE reports (
    id                uuid PRIMARY KEY,
    inspection_id     uuid NOT NULL REFERENCES inspections (id),
    version           bigint NOT NULL,
    content_hash      text NOT NULL,
    file_size_bytes   bigint NOT NULL,
    generated_at      bigint NOT NULL,
    finalized_at      bigint,
    shared_at         bigint,
    object_key        text,
    verified_at       timestamptz,
    revision          bigint NOT NULL,
    last_mutation_id  text NOT NULL,
    updated_by        uuid NOT NULL,
    server_updated_at timestamptz NOT NULL DEFAULT now(),
    change_seq        bigint NOT NULL,
    UNIQUE (inspection_id, version)
);

CREATE INDEX users_seq ON users (change_seq);
CREATE INDEX inspections_seq ON inspections (change_seq);
CREATE INDEX rooms_seq ON rooms (change_seq);
CREATE INDEX findings_seq ON findings (change_seq);
CREATE INDEX photos_seq ON photos (change_seq);
CREATE INDEX reports_seq ON reports (change_seq);
CREATE INDEX rooms_inspection ON rooms (inspection_id);
CREATE INDEX findings_inspection ON findings (inspection_id);
CREATE INDEX photos_inspection ON photos (inspection_id);
CREATE INDEX reports_inspection ON reports (inspection_id);

CREATE TABLE audit_events (
    id         bigserial PRIMARY KEY,
    type       text NOT NULL,
    user_id    uuid,
    device_id  uuid,
    entity     text,
    details    text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_created ON audit_events (created_at);
