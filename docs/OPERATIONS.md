# Valence server: operations

This is the runbook for running the server. The design and the reasons behind it are in `ARCHITECTURE.md`.

## Requirements

- x86_64 Linux with glibc (Debian or Ubuntu). libsignal ships native libraries for that platform only, so Alpine and ARM64 do not work unless libsignal is built from source.
- Java 21.
- Docker Compose v2 for the local PostgreSQL-backed setup.
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

## Local PostgreSQL with Docker Compose

Copy `.env.example` to `.env`, replace every placeholder with private values and the server certificate/key from the key ceremony, then start the server and PostgreSQL from the repository root:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

```sh
cp .env.example .env
docker compose up --build
```

Compose keeps PostgreSQL on its private network and stores its data in the named `postgres-data` volume. The server waits for PostgreSQL's health check and applies Flyway migrations at startup by default. `docker compose down` stops the services without deleting the data volume; `docker compose down --volumes` deletes it.

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

- Mailboxes, keys, and queued messages persist in PostgreSQL. Rate limits, sockets, and counters belong to one process, so run one instance until cross-node fan-out is implemented.
