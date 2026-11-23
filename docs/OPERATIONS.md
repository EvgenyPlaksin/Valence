# Valence server: operations

This is the runbook for running the server. The design and the reasons behind it are in `ARCHITECTURE.md`.

## Requirements

- x86_64 Linux with glibc (Debian or Ubuntu). libsignal ships native libraries for that platform only, so Alpine and ARM64 do not work unless libsignal is built from source.
- Java 21.
- A writable temporary directory. As far as I know libsignal unpacks its native library there at startup. Check this if you run with a read-only root filesystem, and mount a tmpfs on `/tmp`.

## Configuration

Everything comes from environment variables. None of the secrets below belong in Git.

| Variable | Required | Meaning |
|---|---|---|
| `PORT` | No | Listening port, default 8081 |
| `VALENCE_CHALLENGE_KEY` | Yes | At least 32 random bytes, Base64URL. Signs registration and login challenges |
| `VALENCE_TOKEN_KEY` | Yes | At least 32 random bytes, Base64URL, different from the challenge key. Signs access tokens |
| `VALENCE_SERVER_CERTIFICATE` | Yes | The server certificate signed by the trust root |
| `VALENCE_CERTIFICATE_KEY` | Yes | The private key that matches the server certificate |
| `VALENCE_TRUST_PROXY_HEADERS` | No | `true` only behind exactly one trusted reverse proxy that appends `X-Forwarded-For` |
| `VALENCE_METRICS_TOKEN` | No | At least 16 characters. Enables `GET /metrics` for requests with this bearer token |

The server stops at startup with a clear message when a required value is missing, when the two keys are identical, or when the certificate key does not match the server certificate.

Random keys, PowerShell:

```powershell
function New-Key { $b = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32); [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_') }
```

Random keys, shell:

```sh
openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'
```

## Key ceremony

The trust root private key must never reach the server or any machine that holds the repository checkout for daily work.

1. On an offline machine: `./gradlew -q generateTrustRoot`. Store the private key offline with a backup, and pin the public key in the app's build configuration.
2. Where the server's secrets live: `./gradlew -q generateServerKey`. Put the private key in `VALENCE_CERTIFICATE_KEY` and send the public key to whoever holds the trust root.
3. On the offline machine, with the trust root private key in `VALENCE_TRUST_ROOT_PRIVATE_KEY`: `./gradlew -q signServerCertificate --args="<server public key> <key id>"`.
4. Put the result in `VALENCE_SERVER_CERTIFICATE`.

To rotate the server key, repeat steps 2 to 4 with a new key id. No app update is needed. Replacing the trust root needs an app update.

`./gradlew -q generateCertificateKeys` prints all keys at once. It is for development only.

## Container

Build from the repository root:

```sh
docker build --platform linux/amd64 -f server/Dockerfile -t valence-server .
```

Run:

```sh
docker run -p 8081:8081 \
  -e VALENCE_CHALLENGE_KEY=... -e VALENCE_TOKEN_KEY=... \
  -e VALENCE_SERVER_CERTIFICATE=... -e VALENCE_CERTIFICATE_KEY=... \
  valence-server
```

The container runs as an unprivileged user. Use `GET /health` as the readiness probe.

## Behind a reverse proxy

- Terminate TLS at the proxy, and allow WebSocket upgrades on `/v1/socket`.
- The server pings every 20 seconds and closes a silent connection after 40 seconds, so the proxy's idle timeout must be longer.
- Set `VALENCE_TRUST_PROXY_HEADERS=true` only when exactly one proxy hop appends `X-Forwarded-For`. Otherwise the rate limits see the proxy's address for every client.

## Shutdown

On SIGTERM the server closes every open socket with code 1001 and then stops after the engine's grace period. Clients reconnect and resume from their cursor, so nothing is lost. The default `docker stop` timeout of 10 seconds is enough.

## Metrics

Set `VALENCE_METRICS_TOKEN` and scrape `GET /metrics` with `Authorization: Bearer <token>`. The output is in the Prometheus text format:

- `valence_http_responses_total{scope,status}`
- `valence_socket_connections_opened_total`
- `valence_messages_delivered_total`
- `valence_invalid_frames_total`
- `valence_messages_purged_total`
- `valence_queue_depth` and `valence_active_connections`

The counters are aggregate. No mailbox ID, key, token, or path appears in them.

## Limits of the current version

- Accounts and queued messages live in memory, so a restart loses them. PostgreSQL persistence is the next large step.
- Rate limits, sockets, and counters belong to one process, so run one instance.
