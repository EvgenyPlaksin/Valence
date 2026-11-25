# Valence server: operations

This is the runbook for running the server. The design and the reasons behind it are in `ARCHITECTURE.md`.

## Requirements

- x86_64 Linux with glibc (Debian or Ubuntu). libsignal ships native libraries for that platform only, so Alpine and ARM64 do not work unless libsignal is built from source.
- Java 21.
- Docker Compose v2 for the local PostgreSQL- and Redis-backed setup.
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
| `VALENCE_DATABASE_URL` | Yes | PostgreSQL JDBC URL, without credentials |
| `VALENCE_DATABASE_USER` | Yes | PostgreSQL username |
| `VALENCE_DATABASE_PASSWORD` | Yes | PostgreSQL password |
| `VALENCE_DB_MAX_CONNECTIONS` | No | Hikari connection limit and matching database-dispatcher parallelism, default 10 |
| `VALENCE_DB_AUTO_MIGRATE` | No | Run Flyway migrations at startup, default `true`; when `false`, startup fails if migrations are pending |
| `VALENCE_SHUTDOWN_DRAIN_MS` | No | Keep existing WebSockets open after readiness changes to 503, default `0`, maximum `60000` |
| `VALENCE_REDIS_URL` | No | Private Redis URL for cross-node Pub/Sub, for example `redis://` or `rediss://`; credentials must be ACL-limited and are never logged |
| `VALENCE_REDIS_CHANNEL_PREFIX` | With Redis | Environment-specific channel prefix; Redis is disabled if the URL is unset |
| `REDIS_PASSWORD` | Local Compose | Password for the Compose-only ACL user, used to form `VALENCE_REDIS_URL` |
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

Run the Gradle tasks from `server/`. Key-generation tasks require `-PkeyOutputDirectory` to name an existing absolute directory outside every Git worktree. Use a directory owned by the operator. Private key files are written with owner-only permissions; the tools print public keys and file paths, never private key contents.

Linux:

```sh
key_directory="$HOME/valence-keys"
mkdir -p "$key_directory"
chmod 700 "$key_directory"
./gradlew -PkeyOutputDirectory="$key_directory" generateTrustRoot
```

PowerShell:

```powershell
$keyDirectory = Join-Path $HOME "ValenceKeys"
New-Item -ItemType Directory -Path $keyDirectory -Force | Out-Null
.\gradlew.bat -PkeyOutputDirectory="$keyDirectory" generateTrustRoot
```

1. On a dedicated offline machine, generate the trust root as shown above. Keep `trust-root-private.key` offline with a secure backup, and pin only the printed public key in the app's build configuration.
2. Where the server's secrets live, create a private output directory and run `generateServerKey` from `server/` with `-PkeyOutputDirectory=<absolute directory>`: on Linux, `./gradlew -PkeyOutputDirectory="$key_directory" generateServerKey`; in PowerShell, `.\gradlew.bat -PkeyOutputDirectory="$keyDirectory" generateServerKey`. Store `server-signing-private.key` in the server's secret manager as `VALENCE_CERTIFICATE_KEY`, and send only its public key to whoever holds the trust root.
3. On the offline machine, load `trust-root-private.key` into `VALENCE_TRUST_ROOT_PRIVATE_KEY` through the process environment, then sign the server certificate using the public key and key id as Gradle properties:

   ```sh
   VALENCE_TRUST_ROOT_PRIVATE_KEY="$(cat "$key_directory/trust-root-private.key")" \
     ./gradlew --no-daemon -PserverPublicKey="<server public key>" -PkeyId=1 signServerCertificate
   ```

   ```powershell
   $env:VALENCE_TRUST_ROOT_PRIVATE_KEY = [System.IO.File]::ReadAllText("$keyDirectory\trust-root-private.key")
   try {
       .\gradlew.bat --no-daemon -PserverPublicKey="<server public key>" -PkeyId=1 signServerCertificate
   }
   finally {
       Remove-Item Env:VALENCE_TRUST_ROOT_PRIVATE_KEY
   }
   ```

   `--no-daemon` avoids retaining the private-key environment variable in a persistent Gradle daemon. Do not print the environment variable or pass the private key as a command-line argument or Gradle property. The signing task prints the certificate, not the private key.
4. Put the resulting certificate in `VALENCE_SERVER_CERTIFICATE`.

To rotate the server key, repeat steps 2 to 4 with a new key id. No app update is needed. Replacing the trust root needs an app update.

`generateCertificateKeys` is for development only. It also requires `-PkeyOutputDirectory=<absolute directory outside the repository>`, writes private keys with owner-only permissions, and does not print private key contents. Do not use it to create production keys.

## Local PostgreSQL and Redis with Docker Compose

Copy `.env.example` to `.env`, replace every placeholder with private values and the server certificate/key from the key ceremony, then start the server and PostgreSQL from the repository root:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

```sh
cp .env.example .env
docker compose up --build
```

Compose keeps PostgreSQL and Redis on their private network; Redis has no published host port and carries no durable application data. PostgreSQL data is stored in the named `postgres-data` volume. The server waits for PostgreSQL's health check and applies Flyway migrations at startup by default, but it does not wait for Redis. If Redis is unavailable, the server starts and uses PostgreSQL polling to deliver messages and detect superseded sockets.

Redis 8.12.0 is selected under its AGPLv3 licensing option. Configure the application with an ACL-limited Redis user that can publish and subscribe only to the Valence environment's channel. Use TLS (`rediss://`) when the Redis connection crosses a trust boundary. Do not expose the Redis port publicly or include Redis credentials in logs or diagnostics. Pub/Sub messages contain mailbox routing/generation hints only, never message envelopes, tokens, or keys.

`docker compose down` stops the services without deleting the PostgreSQL data volume; `docker compose down --volumes` deletes it.

The database volume is not a backup. Create and store PostgreSQL backups separately. For a plain SQL dump:

```sh
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > valence.sql
```

PowerShell:

```powershell
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > /tmp/valence.sql'
docker compose cp db:/tmp/valence.sql .\valence.sql
docker compose exec -T db rm /tmp/valence.sql
```

Restore into an empty database:

```sh
docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' < valence.sql
```

PowerShell:

```powershell
docker compose cp .\valence.sql db:/tmp/valence.sql
docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB" < /tmp/valence.sql'
docker compose exec -T db rm /tmp/valence.sql
```

Protect backups as sensitive data, store them encrypted, and periodically verify that they can be restored.

The application image can also be built independently from the repository root:

```sh
docker build --platform linux/amd64 -f server/Dockerfile -t valence-server .
```

The container runs as an unprivileged user. Configure the database variables when running it outside Compose. Use `GET /health` for process liveness and `GET /ready` for database-backed readiness.

## PostgreSQL integration tests

From `server/`, run `.\gradlew.bat integrationTest` on Windows or `./gradlew integrationTest` on Linux/macOS. Testcontainers starts PostgreSQL 17 and requires a working Docker engine. If Testcontainers cannot connect to Docker Desktop, the task can use an already-running local PostgreSQL 17 instance by setting `VALENCE_TEST_DATABASE_URL`, `VALENCE_TEST_DATABASE_USER`, and `VALENCE_TEST_DATABASE_PASSWORD`. The external database name must end in `_test`; the tests truncate the server tables before each test, so never point these variables at a production or shared database.

The integration task also starts Redis 8.12.0 for Pub/Sub tests. Test coverage includes two independent server instances sharing PostgreSQL and Redis, stale confirmation fencing, Redis-unavailable polling, reconnect catch-up, and purge-lock contention.

## Multiple server instances

Run server instances behind a load balancer, with every instance using the same PostgreSQL database and Redis endpoint. Redis notifications reduce delivery latency; they are not required for correctness. If Redis is disconnected, connected sockets recover through the PostgreSQL polling path (30 seconds initially, with jitter). `/ready` reports PostgreSQL readiness, returns 503 as soon as shutdown drain begins, and does not remove a server from service solely because Redis is unavailable.

Each socket connection advances the mailbox's PostgreSQL generation. Confirms from an older generation are rejected in the same transaction that would delete inbox rows. Apply the additive socket-state migration before deploying the new server code, and do not enable multi-instance traffic or rely on generation fencing until every active server runs the fencing-capable version. Keep rolling migrations backward-compatible with old and new binaries; Flyway's migration lock serializes migration attempts but does not make incompatible schema changes safe during a rolling deploy.

Budget the database connection pool across the fleet:

```text
server instance count × VALENCE_DB_MAX_CONNECTIONS + operational headroom
    <= PostgreSQL max_connections
```

Monitor the per-node Hikari gauges and acquisition counters listed below. `valence_hikari_threads_awaiting_connection` is instantaneous; use the cumulative `valence_hikari_connection_timeouts_total` and `valence_hikari_connection_acquire_nanos_total` to retain evidence of contention between scrapes.

Each idle resumed socket performs one indexed existence/generation check per polling interval. The interval is jittered from 15 to 45 seconds (30 seconds on average). The approximate poll query rate is `idle resumed sockets / poll interval seconds`; at 100,000 sockets and a 30-second average this is about 3,300 checks per second, or roughly 7–17 concurrently occupied database connections at 2–5 ms per query. The scale test must state whether its socket count is fleet-wide or per-node, measure query latency and database CPU, and keep Hikari waiters at zero for the selected deployment topology. If the pool or database has insufficient headroom, test a 60–120 second interval before adding a more complex batching mechanism. Reconnects also write a new socket generation; clients need jittered reconnect backoff, and the reconnect-storm test must measure generation-write latency and bounded database concurrency. Redis reconnect catch-up is capped at eight concurrent generation checks per server instance.

The bounded Redis publisher queue is FIFO and can contain repeated wake notices for a hot mailbox. These notices carry no message data; overflow is counted and PostgreSQL polling recovers dropped wakes. Add per-mailbox coalescing only if queue metrics show it is needed.

The expiry purge runs on every process but is guarded by `pg_try_advisory_xact_lock`; instances that do not acquire the transaction-level lock skip that run. Rate-limit buckets remain process-local in this release, so effective limits may increase with the instance count. Keep coarse limits at the load balancer or tune per-instance limits until shared rate limiting is designed separately.

## Behind a reverse proxy

- Terminate TLS at the proxy, and allow WebSocket upgrades on `/v1/socket`.
- The server pings every 20 seconds and closes a silent connection after 40 seconds, so the proxy's idle timeout must be longer.
- Set `VALENCE_TRUST_PROXY_HEADERS=true` only when exactly one proxy hop appends `X-Forwarded-For`. Otherwise the rate limits see the proxy's address for every client.

## Shutdown

On SIGTERM the server immediately marks `/ready` unavailable (503) while `/health` stays 200, and refuses new WebSocket upgrades with code 1001 before they advance a mailbox generation. Existing sockets remain open for `VALENCE_SHUTDOWN_DRAIN_MS`, then close with code 1001. The default is 0; set a positive delay only when it fits inside the container's stop timeout. Keep the delay plus the engine's shutdown time within Docker's default 10-second `docker stop` budget. Clients reconnect and resume from their cursor, so nothing is lost.

## Metrics

Set `VALENCE_METRICS_TOKEN` and scrape `GET /metrics` with `Authorization: Bearer <token>`. The output is in the Prometheus text format:

- `valence_http_responses_total{scope,status}`
- `valence_socket_connections_opened_total`
- `valence_messages_delivered_total`
- `valence_invalid_frames_total`
- `valence_messages_purged_total`
- `valence_hikari_connections_active`, `valence_hikari_connections_idle`, `valence_hikari_connections_total`, `valence_hikari_connections_max`, and `valence_hikari_threads_awaiting_connection`
- `valence_hikari_connection_acquire_attempts_total`, `valence_hikari_connection_acquire_nanos_total` (nanoseconds), and `valence_hikari_connection_timeouts_total`
- Redis publish failures and publisher queue overflows
- Redis subscriber failures, malformed notices, catch-up drops/failures, and connection gauges
- `valence_redis_publish_queue_depth`
- `valence_queue_depth` and `valence_active_connections`

The counters are aggregate. No mailbox ID, key, token, or path appears in them. `valence_hikari_threads_awaiting_connection` is an instantaneous gauge and may miss brief waits between scrapes; the cumulative acquisition duration and timeout counters retain evidence of completed waits and timeouts.

## Limits of the current version

- Mailboxes, keys, messages, and socket generations persist in PostgreSQL. Socket registries, rate limits, and counters are process-local. Redis Pub/Sub provides cross-node wake-ups, with PostgreSQL polling as the recovery path; rate limits are not shared across instances.
