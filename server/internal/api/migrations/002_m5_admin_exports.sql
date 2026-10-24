-- M5: admin export/archive, retention settings, device sync reports, device audit events.
-- Additive only: no existing column changes meaning and no row is removed.

-- An export is a month-sized zip of verified objects, downloaded and checked by an admin's phone.
-- manifest and data_json are frozen at creation, so the zip's bytes (and Content-Length) never change.
CREATE TABLE exports (
    id                     bigserial PRIMARY KEY,
    label                  text NOT NULL,
    status                 text NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED', 'VERIFIED', 'ARCHIVED')),
    created_by             uuid NOT NULL REFERENCES users (id),
    created_at             timestamptz NOT NULL DEFAULT now(),
    entry_count            integer NOT NULL,
    objects_bytes          bigint NOT NULL,
    zip_bytes              bigint NOT NULL,
    manifest               bytea NOT NULL,
    manifest_sha256        text NOT NULL,
    data_json              bytea NOT NULL,
    verified_at            timestamptz,
    verified_by            uuid REFERENCES users (id),
    archived_at            timestamptz,
    download_token_hash    bytea,
    download_token_expires timestamptz
);

CREATE TABLE export_items (
    export_id bigint NOT NULL REFERENCES exports (id),
    seq       integer NOT NULL,
    kind      text NOT NULL CHECK (kind IN ('PHOTO', 'REPORT')),
    object_id uuid NOT NULL,
    path      text NOT NULL,
    size      bigint NOT NULL,
    sha256    text NOT NULL,
    crc32     bigint NOT NULL,
    PRIMARY KEY (export_id, seq),
    UNIQUE (export_id, kind, object_id)
);
CREATE INDEX export_items_object ON export_items (object_id);

-- export_verified_at: the object is inside an export an admin has verified (phones may then clean up).
-- archived_at: the object file was removed from the server; the export zip is the copy.
ALTER TABLE photos ADD COLUMN export_verified_at timestamptz;
ALTER TABLE photos ADD COLUMN archived_at timestamptz;
ALTER TABLE photos ADD COLUMN archived_export_id bigint REFERENCES exports (id);
ALTER TABLE reports ADD COLUMN export_verified_at timestamptz;
ALTER TABLE reports ADD COLUMN archived_at timestamptz;
ALTER TABLE reports ADD COLUMN archived_export_id bigint REFERENCES exports (id);

-- What each phone last reported about its own queue (pending items, failures), for the admin view.
ALTER TABLE devices ADD COLUMN sync_report jsonb;
ALTER TABLE devices ADD COLUMN sync_reported_at timestamptz;

-- Events recorded on phones (e.g. a local original removed by retention cleanup), uploaded once.
ALTER TABLE audit_events ADD COLUMN client_event_id uuid UNIQUE;
CREATE INDEX audit_type ON audit_events (type);

CREATE TABLE settings (
    key        text PRIMARY KEY,
    value      text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid REFERENCES users (id)
);
