CREATE TABLE mailbox (
    mailbox_id TEXT PRIMARY KEY,
    identity_key BYTEA NOT NULL,
    signed_prekey_id INTEGER NOT NULL,
    signed_prekey BYTEA NOT NULL,
    signed_prekey_sig BYTEA NOT NULL,
    kyber_prekey_id INTEGER NOT NULL,
    kyber_prekey BYTEA NOT NULL,
    kyber_prekey_sig BYTEA NOT NULL,
    delivery_key_hash BYTEA NOT NULL,
    next_server_seq BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE one_time_prekey (
    prekey_sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    mailbox_id TEXT NOT NULL REFERENCES mailbox(mailbox_id) ON DELETE CASCADE,
    prekey_id INTEGER NOT NULL,
    public_key BYTEA NOT NULL,
    PRIMARY KEY (mailbox_id, prekey_id),
    UNIQUE (mailbox_id, prekey_sequence)
);

CREATE INDEX idx_one_time_prekey_fifo ON one_time_prekey (mailbox_id, prekey_sequence ASC);

CREATE TABLE inbox_message (
    mailbox_id TEXT NOT NULL REFERENCES mailbox(mailbox_id) ON DELETE CASCADE,
    server_seq BIGINT NOT NULL CHECK (server_seq > 0),
    server_ts TIMESTAMPTZ NOT NULL,
    envelope BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (mailbox_id, server_seq)
);

CREATE INDEX idx_inbox_expiry ON inbox_message (expires_at);
