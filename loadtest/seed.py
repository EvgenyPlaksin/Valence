import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "valence-loadtest")
TOKEN_KEY_VARIABLE = "VALENCE_TOKEN_KEY"
CALIBRATION_MAILBOX_COUNT = 5000


def encode_url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def decode_url(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def mailbox_id():
    return "mbx_" + base64.b32encode(secrets.token_bytes(16)).decode("ascii").rstrip("=").lower()


def access_token(subject, signing_key):
    now = int(time.time())
    header = encode_url(b'{"alg":"HS256","typ":"JWT"}')
    claims = encode_url(json.dumps({"sub": subject, "iat": now, "exp": now + 7200}, separators=(",", ":")).encode())
    signing_input = f"{header}.{claims}".encode("ascii")
    signature = hmac.new(signing_key, signing_input, hashlib.sha256).digest()
    return f"{signing_input.decode('ascii')}.{encode_url(signature)}"


def run_psql(sql):
    command = [
        "docker",
        "compose",
        "-p",
        PROJECT,
        "-f",
        "compose.yaml",
        "-f",
        "loadtest/compose.loadtest.yaml",
        "exec",
        "-T",
        "db",
        "sh",
        "-c",
        'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" "$POSTGRES_DB"',
    ]
    result = subprocess.run(command, cwd=ROOT, input=sql, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"PostgreSQL fixture setup failed with exit code {result.returncode}: {result.stderr}")


def sql_quote(value):
    return "'" + value.replace("'", "''") + "'"


def create_fixtures(count, output, expired_count=0, quota_count=0):
    encoded_key = os.environ.get(TOKEN_KEY_VARIABLE)
    if not encoded_key:
        raise RuntimeError(f"{TOKEN_KEY_VARIABLE} is required")
    signing_key = decode_url(encoded_key)
    if len(signing_key) < 32:
        raise RuntimeError("The temporary token key must contain at least 32 bytes")

    mailboxes = []
    rows = []
    for _ in range(count):
        identifier = mailbox_id()
        delivery_key = secrets.token_bytes(16)
        digest = hashlib.sha256(delivery_key).hexdigest()
        mailboxes.append(
            {
                "mailboxId": identifier,
                "accessToken": access_token(identifier, signing_key),
                "deliveryKey": encode_url(delivery_key),
            },
        )
        rows.append(
            "("
            + ", ".join(
                [
                    sql_quote(identifier),
                    "decode('01', 'hex')",
                    "1",
                    "decode('02', 'hex')",
                    "decode('03', 'hex')",
                    "1",
                    "decode('04', 'hex')",
                    "decode('05', 'hex')",
                    f"decode('{digest}', 'hex')",
                ],
            )
            + ")",
        )

    sql_statements = [
        "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;",
        "SELECT pg_stat_statements_reset();",
        "TRUNCATE TABLE inbox_message, mailbox_socket_state, mailbox CASCADE;",
        "INSERT INTO mailbox (mailbox_id, identity_key, signed_prekey_id, signed_prekey, signed_prekey_sig, "
        "kyber_prekey_id, kyber_prekey, kyber_prekey_sig, delivery_key_hash) VALUES\n"
        + ",\n".join(rows)
        + ";",
        "INSERT INTO mailbox_socket_state (mailbox_id, active_socket_generation) SELECT mailbox_id, 0 FROM mailbox;",
    ]

    if expired_count > 0 and mailboxes:
        expired_mailbox = (
            mailboxes[CALIBRATION_MAILBOX_COUNT]
            if len(mailboxes) >= CALIBRATION_MAILBOX_COUNT + 3
            else mailboxes[0]
        )
        target_mailbox = expired_mailbox["mailboxId"]
        expired_rows = []
        for seq in range(1, expired_count + 1):
            expired_rows.append(
                f"({sql_quote(target_mailbox)}, {seq}, now() - interval '1 hour', decode('01', 'hex'), now() - interval '10 minutes')"
            )
        sql_statements.append(
            "INSERT INTO inbox_message (mailbox_id, server_seq, server_ts, envelope, expires_at) VALUES\n"
            + ",\n".join(expired_rows)
            + ";"
        )
        sql_statements.append(
            f"UPDATE mailbox SET next_server_seq = {expired_count} WHERE mailbox_id = {sql_quote(target_mailbox)};"
        )

    if quota_count > 0 and len(mailboxes) >= 2:
        quota_mailbox = mailboxes[-1]["mailboxId"]
        quota_rows = []
        for seq in range(1, quota_count + 1):
            quota_rows.append(
                f"({sql_quote(quota_mailbox)}, {seq}, now(), decode('01', 'hex'), now() + interval '1 hour')"
            )
        sql_statements.append(
            "INSERT INTO inbox_message (mailbox_id, server_seq, server_ts, envelope, expires_at) VALUES\n"
            + ",\n".join(quota_rows)
            + ";"
        )
        sql_statements.append(
            f"UPDATE mailbox SET next_server_seq = {quota_count} WHERE mailbox_id = {sql_quote(quota_mailbox)};"
        )

    run_psql("\n".join(sql_statements) + "\n")

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"mailboxes": mailboxes}, separators=(",", ":")), encoding="utf-8")
    try:
        output.chmod(0o600)
    except OSError:
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--seed-expired", type=int, default=0)
    parser.add_argument("--seed-quota", type=int, default=0)
    args = parser.parse_args()
    if not 1 <= args.count <= 10000:
        raise SystemExit("--count must be between 1 and 10000")
    create_fixtures(args.count, args.manifest, args.seed_expired, args.seed_quota)


if __name__ == "__main__":
    main()
