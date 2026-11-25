CREATE TABLE one_time_kyber_prekey (
    prekey_sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    mailbox_id TEXT NOT NULL REFERENCES mailbox(mailbox_id) ON DELETE CASCADE,
    prekey_id INTEGER NOT NULL CHECK (prekey_id > 0),
    public_key BYTEA NOT NULL,
    signature BYTEA NOT NULL,
    PRIMARY KEY (mailbox_id, prekey_id),
    UNIQUE (mailbox_id, prekey_sequence)
);

CREATE INDEX idx_one_time_kyber_prekey_fifo ON one_time_kyber_prekey (mailbox_id, prekey_sequence ASC);
