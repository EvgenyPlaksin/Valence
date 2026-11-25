#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-all}"
VUS="${VUS:-200}"
HOLD_SECONDS="${HOLD_SECONDS:-900}"
SEND_RATE="${SEND_RATE:-20}"
PROXY_PORT="${LOADTEST_PROXY_PORT:-18080}"

case "${MODE}" in
    smoke|calibrate|fencing|quota|soak|all) ;;
    *)
        echo "Mode must be one of: smoke, calibrate, fencing, quota, soak, all" >&2
        exit 1
        ;;
esac
if [[ "${MODE}" == "all" || "${MODE}" == "soak" ]]; then
    if ! [[ "${HOLD_SECONDS}" =~ ^[0-9]+$ ]] || [[ "${HOLD_SECONDS}" -lt 900 ]]; then
        echo "Baseline modes require at least 900 seconds of soak" >&2
        exit 1
    fi
fi

generate_secret() {
    openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_NAME="valence-loadtest"
COMPOSE_FILES=("-f" "compose.yaml" "-f" "loadtest/compose.loadtest.yaml")
ENV_CREATED=0
TELEMETRY_PID=0

if [[ -e "${REPO_ROOT}/.env" ]]; then
    echo "Refusing to overwrite the existing repository .env file" >&2
    exit 1
fi

if [[ -n "$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT_NAME}")" ]] ||
    [[ -n "$(docker volume ls -q --filter "name=${PROJECT_NAME}_")" ]]; then
    echo "Refusing to reuse or remove existing ${PROJECT_NAME} Docker resources" >&2
    exit 1
fi

TEMP_KEY_DIR="$(mktemp -d /tmp/valence-loadtest-keys.XXXXXX)"
RUN_DIR="$(mktemp -d /tmp/valence-loadtest-run.XXXXXX)"

cleanup() {
    if [[ "${TELEMETRY_PID}" -gt 0 ]]; then
        kill "${TELEMETRY_PID}" 2>/dev/null || true
        wait "${TELEMETRY_PID}" 2>/dev/null || true
    fi
    echo "Tearing down Compose topology..."
    cd "${REPO_ROOT}"
    docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" down -v || true
    if [[ "${ENV_CREATED}" -eq 1 ]]; then
        rm -f "${REPO_ROOT}/.env"
    fi
    rm -f "${RUN_DIR}/manifest.json"
    echo "Removing temporary key directory..."
    rm -rf "${TEMP_KEY_DIR}"
}
trap cleanup EXIT

echo "Generating temporary certificate keys..."
cd "${REPO_ROOT}/server"
./gradlew --no-daemon -PkeyOutputDirectory="${TEMP_KEY_DIR}" generateCertificateKeys

SERVER_CERT="$(tr -d '\r\n' < "${TEMP_KEY_DIR}/server-certificate.txt")"
SERVER_KEY="$(tr -d '\r\n' < "${TEMP_KEY_DIR}/server-signing-private.key")"

CHALLENGE_KEY="$(generate_secret)"
TOKEN_KEY="$(generate_secret)"
METRICS_TOKEN="valence-loadtest-metrics-$(generate_secret)"
REDIS_PASSWORD="$(generate_secret)"
POSTGRES_PASSWORD="$(generate_secret)"
REDIS_PREFIX="valence:loadtest:$(generate_secret | cut -c1-8)"

export COMPOSE_PROJECT_NAME="${PROJECT_NAME}"
export POSTGRES_DB="valence_loadtest"
export POSTGRES_USER="valence"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD}"
export REDIS_PASSWORD="${REDIS_PASSWORD}"
export VALENCE_REDIS_CHANNEL_PREFIX="${REDIS_PREFIX}"
export VALENCE_METRICS_TOKEN="${METRICS_TOKEN}"
export VALENCE_CHALLENGE_KEY="${CHALLENGE_KEY}"
export VALENCE_TOKEN_KEY="${TOKEN_KEY}"
export VALENCE_SERVER_CERTIFICATE="${SERVER_CERT}"
export VALENCE_CERTIFICATE_KEY="${SERVER_KEY}"
export LOADTEST_RUN_DIR="${RUN_DIR}"
export LOADTEST_PROXY_PORT="${PROXY_PORT}"
export PORT="18081"
export SERVER_B_PORT="18082"
export LOADTEST_STATS_PORT="18404"

set -o noclobber
: > "${REPO_ROOT}/.env"
ENV_CREATED=1
set +o noclobber
cat <<EOF >> "${REPO_ROOT}/.env"
POSTGRES_DB=valence_loadtest
POSTGRES_USER=valence
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
REDIS_PASSWORD=${REDIS_PASSWORD}
VALENCE_REDIS_CHANNEL_PREFIX=${REDIS_PREFIX}
VALENCE_REDIS_URL=redis://valence:${REDIS_PASSWORD}@redis:6379
VALENCE_DATABASE_URL=jdbc:postgresql://db:5432/valence_loadtest
VALENCE_DATABASE_USER=valence
VALENCE_DATABASE_PASSWORD=${POSTGRES_PASSWORD}
VALENCE_DB_MAX_CONNECTIONS=10
VALENCE_DB_AUTO_MIGRATE=true
VALENCE_TRUST_PROXY_HEADERS=true
VALENCE_METRICS_TOKEN=${METRICS_TOKEN}
VALENCE_CHALLENGE_KEY=${CHALLENGE_KEY}
VALENCE_TOKEN_KEY=${TOKEN_KEY}
VALENCE_SERVER_CERTIFICATE=${SERVER_CERT}
VALENCE_CERTIFICATE_KEY=${SERVER_KEY}
JAVA_TOOL_OPTIONS=-Xms128m -Xmx512m -Xss512k -XX:+UseG1GC
EOF

cd "${REPO_ROOT}"
echo "Starting Compose topology..."
docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" up -d --build

echo "Waiting for HAProxy to report /ready..."
READY=0
for _ in $(seq 1 40); do
    sleep 2
    if curl -fs "http://127.0.0.1:${PROXY_PORT}/ready" > /dev/null 2>&1; then
        READY=1
        break
    fi
done

if [ "${READY}" -ne 1 ]; then
    echo "Topology failed to report /ready" >&2
    exit 1
fi
echo "Topology is ready."

MANIFEST_HOST="${RUN_DIR}/manifest.json"
MANIFEST_DOCKER="/run/manifest.json"

SEED_COUNT=500
if [ "${MODE}" = "smoke" ]; then
    SEED_COUNT=20
elif [ "${MODE}" = "all" ] || [ "${MODE}" = "soak" ]; then
    SEED_COUNT=5003
elif [ "${MODE}" = "calibrate" ]; then
    SEED_COUNT=5000
fi

echo "Seeding ${SEED_COUNT} mailboxes..."
python3 loadtest/seed.py --count "${SEED_COUNT}" --manifest "${MANIFEST_HOST}" --seed-expired 10 --seed-quota 1000

run_k6() {
    local scenario="$1"
    local vus="$2"
    local hold="$3"
    local rate="$4"
    local summary_name="k6_${scenario}_vus${vus}_$(date +%Y%m%d_%H%M%S).json"
    local stats_file="${RUN_DIR}/docker_stats_${scenario}_vus${vus}.txt"
    (
        while true; do
            printf 'sampled_at=%s\n' "$(date --iso-8601=seconds)" >> "${stats_file}"
            docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.PIDs}}' >> "${stats_file}"
            sleep 2
        done
    ) &
    local stats_pid=$!
    if docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" run --rm \
        -e MODE="${scenario}" \
        -e MANIFEST="${MANIFEST_DOCKER}" \
        -e HTTP_URL="http://haproxy:8080" \
        -e NODE_A_URL="http://server:8081" \
        -e NODE_B_URL="http://server-b:8081" \
        -e VALENCE_METRICS_TOKEN="${METRICS_TOKEN}" \
        -e VUS="${vus}" \
        -e HOLD_SECONDS="${hold}" \
        -e SEND_RATE="${rate}" \
        k6 run --summary-export "/run/${summary_name}" /scripts/scenarios.js; then
        local status=0
    else
        local status=$?
    fi
    kill "${stats_pid}" 2>/dev/null || true
    wait "${stats_pid}" 2>/dev/null || true
    if [[ "${status}" -eq 0 ]]; then
        if ! python3 - "${RUN_DIR}/${summary_name}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as summary_file:
    checks = json.load(summary_file).get("metrics", {}).get("checks", {})
if not checks or checks.get("fails", 1) > 0:
    raise SystemExit(f"k6 scenario had failed checks: {checks}")
PY
        then
            status=1
        fi
    fi
    return "${status}"
}

wait_for_redis_recovery() {
    for _ in $(seq 1 30); do
        if curl -fsS -H "Authorization: Bearer ${METRICS_TOKEN}" "http://127.0.0.1:18081/metrics" |
            grep -Eq 'valence_redis_subscriber_connected[[:space:]]+1' &&
            curl -fsS -H "Authorization: Bearer ${METRICS_TOKEN}" "http://127.0.0.1:18082/metrics" |
                grep -Eq 'valence_redis_subscriber_connected[[:space:]]+1'; then
            return 0
        fi
        sleep 1
    done
    echo "Redis subscribers did not recover on both nodes" >&2
    return 1
}

wait_for_node_a_ready() {
    for _ in $(seq 1 90); do
        if curl -fsS "http://127.0.0.1:18081/ready" > /dev/null; then
            return 0
        fi
        sleep 1
    done
    echo "Node A did not become ready after the SIGTERM restart" >&2
    return 1
}

verify_soak_summary() {
    local summary
    summary="$(find "${RUN_DIR}" -maxdepth 1 -name 'k6_soak_vus*.json' -print | sort | tail -n 1)"
    if [[ -z "${summary}" ]]; then
        echo "The soak k6 summary was not written" >&2
        return 1
    fi
    python3 - "${summary}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as summary_file:
    metrics = json.load(summary_file)["metrics"]

def count(name):
    return metrics.get(name, {}).get("count", 0)

accepted = count("loadtest_accepted_messages")
delivered = count("loadtest_delivered_messages")
sequence_errors = count("loadtest_sequence_errors")
frame_errors = count("loadtest_frame_errors")
if accepted != delivered or sequence_errors or frame_errors:
    raise SystemExit(
        f"Soak replay invariants failed: accepted={accepted} delivered={delivered} "
        f"sequenceErrors={sequence_errors} frameErrors={frame_errors}"
    )

send = metrics["loadtest_send_latency_ms"]
delivery = metrics["loadtest_delivery_latency_ms"]
print(
    f"Soak accepted={accepted} delivered={delivered} sequenceErrors={sequence_errors} "
    f"frameErrors={frame_errors}"
)
print(
    f"Send latency p95={send.get('p(95)')}ms p99={send.get('p(99)')}ms; "
    f"delivery latency p95={delivery.get('p(95)')}ms p99={delivery.get('p(99)')}ms"
)
PY
}

verify_db_waiters() {
    python3 - "${RUN_DIR}" <<'PY'
import glob
import os
import re
import sys

run_dir = sys.argv[1]
paths = sorted(glob.glob(os.path.join(run_dir, "db_stat_*.txt")))
paths.append(os.path.join(run_dir, "final_postgres_observe.txt"))
samples = []
for path in paths:
    with open(path, encoding="utf-8") as sample_file:
        rows = [line for line in sample_file if re.match(r"^\s*20\d{2}-\d{2}-\d{2} .*\|", line)]
    if not rows:
        raise SystemExit(f"PostgreSQL wait sample missing in {os.path.basename(path)}")
    columns = [column.strip() for column in rows[0].split("|")]
    if len(columns) < 5:
        raise SystemExit(f"PostgreSQL wait sample malformed in {os.path.basename(path)}")
    samples.append((int(columns[2]), int(columns[3]), int(columns[4])))

max_non_lock_waiters = max(non_lock_waiters for non_lock_waiters, _, _ in samples)
max_lock_waiters = max(lock_waiters for _, lock_waiters, _ in samples)
max_deadlocks = max(deadlocks for _, _, deadlocks in samples)
with open(os.path.join(run_dir, "database_waiter_summary.txt"), "w", encoding="utf-8") as output:
    output.write(
        f"max_non_lock_waiters={max_non_lock_waiters} "
        f"max_lock_waiters={max_lock_waiters} max_deadlocks={max_deadlocks}\n"
    )
print(
    f"PostgreSQL non-Lock waiters max={max_non_lock_waiters} "
    f"lockWaiters max={max_lock_waiters} deadlocks max={max_deadlocks}"
)
if max_lock_waiters or max_deadlocks:
    raise SystemExit("PostgreSQL lock waiters or deadlocks were observed")
PY
}

sample_telemetry() {
    while true; do
        sleep 20
        local timestamp
        timestamp="$(date +%Y%m%d_%H%M%S)"
        if ! docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" exec -T db sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB" -f /scripts/postgres-observe.sql' > "${RUN_DIR}/db_stat_${timestamp}.txt" 2>&1; then
            printf 'PostgreSQL sampling failed at %s\n' "${timestamp}" >> "${RUN_DIR}/telemetry_errors.txt"
        fi
        if ! docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.PIDs}}' > "${RUN_DIR}/docker_stats_${timestamp}.txt" 2>&1; then
            printf 'Docker stats sampling failed at %s\n' "${timestamp}" >> "${RUN_DIR}/telemetry_errors.txt"
        fi
        if ! docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" exec -T redis sh -c 'redis-cli --user valence -a "$REDIS_PASSWORD" info' > "${RUN_DIR}/redis_info_${timestamp}.txt" 2>&1; then
            printf 'Redis sampling failed at %s\n' "${timestamp}" >> "${RUN_DIR}/telemetry_errors.txt"
        fi
        if ! curl -fsS -H "Authorization: Bearer ${METRICS_TOKEN}" "http://127.0.0.1:18081/metrics" > "${RUN_DIR}/node_a_metrics_${timestamp}.txt"; then
            printf 'Node A metrics sampling failed at %s\n' "${timestamp}" >> "${RUN_DIR}/telemetry_errors.txt"
        fi
        if ! curl -fsS -H "Authorization: Bearer ${METRICS_TOKEN}" "http://127.0.0.1:18082/metrics" > "${RUN_DIR}/node_b_metrics_${timestamp}.txt"; then
            printf 'Node B metrics sampling failed at %s\n' "${timestamp}" >> "${RUN_DIR}/telemetry_errors.txt"
        fi
        if ! docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" exec -T server sh -c 'printf "node_a_fd_count="; ls /proc/1/fd | wc -l' > "${RUN_DIR}/node_a_fd_${timestamp}.txt" 2>&1; then
            printf 'Node A FD sampling failed at %s\n' "${timestamp}" >> "${RUN_DIR}/telemetry_errors.txt"
        fi
        if ! docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" exec -T server-b sh -c 'printf "node_b_fd_count="; ls /proc/1/fd | wc -l' > "${RUN_DIR}/node_b_fd_${timestamp}.txt" 2>&1; then
            printf 'Node B FD sampling failed at %s\n' "${timestamp}" >> "${RUN_DIR}/telemetry_errors.txt"
        fi
    done
}

if [ "${MODE}" = "smoke" ] || [ "${MODE}" = "all" ]; then
    echo "=== Running Smoke Test ==="
    run_k6 "smoke" 10 20 5
fi

if [ "${MODE}" = "quota" ] || [ "${MODE}" = "all" ] || [ "${MODE}" = "soak" ]; then
    echo "=== Running Slow Consumer & Quota Test ==="
    run_k6 "quota" 1 30 0
fi

if [ "${MODE}" = "calibrate" ] || [ "${MODE}" = "all" ] || [ "${MODE}" = "soak" ]; then
    echo "=== Running k6 Calibration: 1,000 idle sockets ==="
    run_k6 "calibrate" 1000 45 0

    echo "=== Running k6 Calibration: 5,000 idle sockets ==="
    run_k6 "calibrate" 5000 45 0
fi

if [ "${MODE}" = "fencing" ] || [ "${MODE}" = "all" ] || [ "${MODE}" = "soak" ]; then
    echo "=== Running Stale-Socket Fencing Test with Redis down ==="
    docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" pause redis
    run_k6 "fencing-down" 1 10 0
    docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" unpause redis
    wait_for_redis_recovery
    echo "=== Running Stale-Socket Fencing Test with Redis up ==="
    run_k6 "fencing-up" 1 10 0
fi

if [ "${MODE}" = "soak" ] || [ "${MODE}" = "all" ]; then
    echo "=== Running Baseline Soak Test ==="
    SOAK_HOLD="${HOLD_SECONDS}"
    sample_telemetry &
    TELEMETRY_PID=$!
    run_k6 "soak" "${VUS}" "${SOAK_HOLD}" "${SEND_RATE}"
    kill "${TELEMETRY_PID}" 2>/dev/null || true
    wait "${TELEMETRY_PID}" 2>/dev/null || true
    TELEMETRY_PID=0
    if [[ -s "${RUN_DIR}/telemetry_errors.txt" ]]; then
        echo "Telemetry sampling failed; see ${RUN_DIR}/telemetry_errors.txt" >&2
        exit 1
    fi
    verify_soak_summary
    curl -fsS -H "Authorization: Bearer ${METRICS_TOKEN}" "http://127.0.0.1:18081/metrics" > "${RUN_DIR}/node_a_metrics_soak_final.txt"
    curl -fsS -H "Authorization: Bearer ${METRICS_TOKEN}" "http://127.0.0.1:18082/metrics" > "${RUN_DIR}/node_b_metrics_soak_final.txt"

    echo "=== Testing SIGTERM Drain Window ==="
    curl -fsS "http://127.0.0.1:18081/ready" > /dev/null
    run_k6 "drain" 1 60 0 > "${RUN_DIR}/k6_drain.log" 2>&1 &
    drain_pid=$!
    socket_ready=0
    for _ in $(seq 1 40); do
        if curl -fsS -H "Authorization: Bearer ${METRICS_TOKEN}" "http://127.0.0.1:18081/metrics" |
            grep -Eq 'valence_active_connections(\{[^}]*\})?[[:space:]]+[1-9][0-9]*'; then
            socket_ready=1
            break
        fi
        sleep 0.5
    done
    if [ "${socket_ready}" -ne 1 ]; then
        kill "${drain_pid}" 2>/dev/null || true
        wait "${drain_pid}" 2>/dev/null || true
        echo "Drain WebSocket did not connect to node A" >&2
        exit 1
    fi
    docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" kill -s SIGTERM server
    READY_FILE="${RUN_DIR}/sigterm_ready_window.csv"
    printf 'sampled_at,status\n' > "${READY_FILE}"
    for _ in $(seq 1 50); do
        status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 1 "http://127.0.0.1:18081/ready" || printf '000')"
        printf '%s,%s\n' "$(date --iso-8601=milliseconds)" "${status}" >> "${READY_FILE}"
        sleep 0.2
    done
    ready_200_count="$(awk -F, '$2 == 200 { count++ } END { print count + 0 }' "${READY_FILE}")"
    echo "Node A /ready samples during SIGTERM: 200=${ready_200_count} total=50; drain WebSocket must close with 1001"
    if ! wait "${drain_pid}"; then
        echo "Drain k6 scenario failed; see ${RUN_DIR}/k6_drain.log" >&2
        exit 1
    fi
    docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" up -d server
    wait_for_node_a_ready

    echo "=== Testing Redis Outage Recovery ==="
    docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" pause redis
    sleep 3
    curl -fs "http://127.0.0.1:${PROXY_PORT}/ready" > /dev/null
    run_k6 "redis-down" 1 55 0
    docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" unpause redis
    wait_for_redis_recovery
    run_k6 "redis-up" 1 10 0
fi

echo "Capturing final telemetry snapshots..."
docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" exec -T db sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB" -f /scripts/postgres-observe.sql' > "${RUN_DIR}/final_postgres_observe.txt"
docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" exec -T redis sh -c 'redis-cli --user valence -a "$REDIS_PASSWORD" info' > "${RUN_DIR}/final_redis_info.txt"
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.PIDs}}' > "${RUN_DIR}/docker_stats_final.txt"
docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" exec -T server sh -c 'printf "node_a_fd_count="; ls /proc/1/fd | wc -l' > "${RUN_DIR}/node_a_fd_final.txt"
docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" exec -T server-b sh -c 'printf "node_b_fd_count="; ls /proc/1/fd | wc -l' > "${RUN_DIR}/node_b_fd_final.txt"
server_logs="$(docker compose -p "${PROJECT_NAME}" "${COMPOSE_FILES[@]}" logs --no-color server server-b)"
connection_error_count="$(printf '%s\n' "${server_logs}" | awk 'tolower($0) ~ /error.*hikaripool|warn.*hikaripool.*(connection|timeout|failed|validation)|hikaripool.*(connection is not available|failed to validate)|connection is not available|sqltransientconnectionexception|too many clients|remaining connection slots|failed to validate connection|connection( to .*)? (refused|reset|timed out)|could not connect to server/ { count++ } END { print count + 0 }')"
printf 'hikari_or_database_connection_error_lines=%s\n' "${connection_error_count}" > "${RUN_DIR}/database_connection_errors.txt"
if [ "${connection_error_count}" -gt 0 ]; then
    echo "Hikari or database connection errors were recorded" >&2
    exit 1
fi
verify_db_waiters

curl -fs -H "Authorization: Bearer ${METRICS_TOKEN}" "http://127.0.0.1:18081/metrics" > "${RUN_DIR}/node_a_metrics.txt"
curl -fs -H "Authorization: Bearer ${METRICS_TOKEN}" "http://127.0.0.1:18082/metrics" > "${RUN_DIR}/node_b_metrics.txt"
if [[ "${MODE}" == "all" || "${MODE}" == "soak" ]]; then
    purged_a="$(awk '$1 == "valence_messages_purged_total" { print $2 }' "${RUN_DIR}/node_a_metrics_soak_final.txt")"
    purged_b="$(awk '$1 == "valence_messages_purged_total" { print $2 }' "${RUN_DIR}/node_b_metrics_soak_final.txt")"
    purged_a="${purged_a:-0}"
    purged_b="${purged_b:-0}"
    if (( purged_a + purged_b != 10 )) || { (( purged_a > 0 )) && (( purged_b > 0 )); }; then
        echo "Purge advisory-lock check failed: nodeA=${purged_a} nodeB=${purged_b} expected a single winner purging 10 fixtures" >&2
        exit 1
    fi
    echo "Purge lock winner: nodeA=${purged_a} nodeB=${purged_b}"
fi

echo "Loadtest completed successfully. Artifacts saved in ${RUN_DIR}"
