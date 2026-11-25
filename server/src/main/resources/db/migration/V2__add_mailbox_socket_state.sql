CREATE TABLE mailbox_socket_state (
    mailbox_id TEXT PRIMARY KEY REFERENCES mailbox(mailbox_id) ON DELETE CASCADE,
    active_socket_generation BIGINT NOT NULL DEFAULT 0
);

INSERT INTO mailbox_socket_state (mailbox_id)
SELECT mailbox_id FROM mailbox;
