#!/usr/bin/env bash
set -euo pipefail
umask 077

image="${1:?usage: smoke-test.sh <image>}"
base="http://localhost:8081"
key_dir="$(mktemp -d)"
container=""
trap 'if [ -n "$container" ]; then docker rm -f "$container" >/dev/null 2>&1 || true; fi; rm -rf "$key_dir"' EXIT

new_key() { openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
expect() {
  if [ "$2" != "$3" ]; then
    echo "FAILED: $1 (expected $2, got $3)"
    docker logs "$container" || true
    exit 1
  fi
  echo "ok: $1"
}

if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  key_dir_mount="$(cygpath -m "$key_dir")"
  MSYS_NO_PATHCONV=1 docker run --rm --entrypoint java --user "$(id -u):$(id -g)" \
    -v "$key_dir_mount:/keys" "$image" -cp "/app/lib/*" \
    com.lnight.valence.server.crypto.CertificateKeyToolKt /keys
else
  docker run --rm --entrypoint java --user "$(id -u):$(id -g)" \
    -v "$key_dir:/keys" "$image" -cp "/app/lib/*" \
    com.lnight.valence.server.crypto.CertificateKeyToolKt /keys
fi
certificate="$(<"$key_dir/server-certificate.txt")"
signing_key="$(<"$key_dir/server-signing-private.key")"
{
  printf 'VALENCE_CHALLENGE_KEY=%s\n' "$(new_key)"
  printf 'VALENCE_TOKEN_KEY=%s\n' "$(new_key)"
  printf 'VALENCE_SERVER_CERTIFICATE=%s\n' "$certificate"
  printf 'VALENCE_CERTIFICATE_KEY=%s\n' "$signing_key"
} > "$key_dir/server.env"

container=$(docker run -d -p 8081:8081 --env-file "$key_dir/server.env" "$image")

for _ in $(seq 1 30); do
  if curl -fsS "$base/health" >/dev/null 2>&1; then break; fi
  sleep 1
done

expect "the health endpoint answers" 200 "$(status "$base/health")"
expect "the challenge endpoint answers" 200 "$(status -X POST "$base/v1/auth/challenge")"
expect "the key endpoints require a token" 401 "$(status "$base/v1/keys/status")"
expect "the metrics endpoint is off by default" 404 "$(status "$base/metrics")"
