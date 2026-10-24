-- AI writing help (finding suggestions, report summary, pre-finalize check) via DeepSeek.
-- Additive only: no existing column changes meaning and no row is removed.

-- The one DeepSeek API key, sealed with AES-256-GCM under GRIHA_SECRET_KEY (server env, never in the
-- database). Write-only over the API: admins only ever see its last four characters.
CREATE TABLE ai_key (
    id         boolean PRIMARY KEY DEFAULT true CHECK (id),
    nonce      bytea NOT NULL,
    ciphertext bytea NOT NULL,
    last4      text NOT NULL,
    set_by     uuid NOT NULL REFERENCES users (id),
    set_at     timestamptz NOT NULL DEFAULT now()
);

-- Requests per user per (India) day, for the daily limit and the admin's monthly usage view.
CREATE TABLE ai_usage (
    user_id    uuid NOT NULL REFERENCES users (id),
    day        date NOT NULL,
    kind       text NOT NULL,
    requests   integer NOT NULL DEFAULT 0,
    failures   integer NOT NULL DEFAULT 0,
    tokens_in  bigint NOT NULL DEFAULT 0,
    tokens_out bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day, kind)
);

-- Overview paragraph for the report's summary page, written (or AI-drafted and accepted) on a phone.
ALTER TABLE inspections ADD COLUMN summary text;
