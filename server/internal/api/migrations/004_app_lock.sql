-- Developer lock: one switch that makes every API request answer 423 APP_LOCKED, and phones show a
-- full-screen "contact your developer" notice. Set only with `home-inspection-api lock` / `unlock` on the server
-- (no HTTP endpoint changes it). Nothing is deleted while locked. Additive only.

CREATE TABLE app_lock (
    id         boolean PRIMARY KEY DEFAULT true CHECK (id),
    locked     boolean NOT NULL DEFAULT false,
    message    text NOT NULL DEFAULT '',
    changed_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO app_lock (locked) VALUES (false);

-- Every lock/unlock, kept apart from audit_events (which the client's admins can read in the app).
CREATE TABLE app_lock_history (
    id         bigserial PRIMARY KEY,
    locked     boolean NOT NULL,
    message    text NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now()
);
