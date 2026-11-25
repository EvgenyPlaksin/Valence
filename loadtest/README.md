# Valence Multi-Instance Baseline Load & Soak Test Harness

This directory contains the baseline load, soak, and resilience test harness for the Valence server running in a multi-instance topology.

## Topology

The topology defined in `compose.loadtest.yaml` includes:
- **2 Ktor Server Nodes (`server`, `server-b`)**: 0.5 CPU, 1 GiB RAM each, `JAVA_TOOL_OPTIONS="-Xms128m -Xmx512m -Xss512k -XX:+UseG1GC"`, `VALENCE_DB_MAX_CONNECTIONS=10`, `VALENCE_TRUST_PROXY_HEADERS=true`.
- **HAProxy (`haproxy`)**: 0.5 CPU, 256 MiB RAM, `leastconn` balancing, `/ready` health checking, WebSocket tunneling timeout 1h, strips client-supplied `X-Forwarded-For` and synthesizes one from the source port. Exposes port 18080 (traffic) and 18404 (stats).
- **PostgreSQL 17 (`db`)**: 1 CPU, 2 GiB RAM, `max_connections=60`, with `pg_stat_statements` preloaded and tracked.
- **Redis 8.12.0 (`redis`)**: 0.5 CPU, 512 MiB RAM, private network only, ACL user matching `OPERATIONS.md`.
- **Grafana k6 (`k6`)**: Containerized runner with 2 CPU, 4 GiB RAM.

## Scenarios

1. **Smoke Test (`smoke`)**: Fast sanity test running 10 VUs and 5 msgs/sec for 20 seconds.
2. **k6 Generator Calibration (`calibrate`)**: Calibrates generator memory and CPU usage at 1,000 and 5,000 idle WebSockets.
3. **Stale-Socket Fencing (`fencing`)**: Validates that when a mailbox connects on node B, a stale confirmation from node A is rejected and closed with code 4000, and that the replacement socket can replay the unconfirmed message, with Redis both available and paused.
4. **Slow Consumer & Quota (`quota`)**: Validates that 1,000 unconfirmed messages succeed, the 1,001st message returns HTTP 429 with `Retry-After`, and unrelated sends continue without head-of-line blocking.
5. **Steady-State Soak (`soak`)**: 200 concurrent resumed WebSockets, ~20 msgs/sec send rate, 250 ms batched confirmations, and periodic socket reconnects.
6. **Failure Injections**:
   - **SIGTERM Drain**: Requires the connected socket to close with code 1001 and records node A `/ready` responses during shutdown.
   - **Redis Outage and Recovery**: Requires a cross-node delivery while Redis is paused (PostgreSQL polling) and a delivery under 15 seconds after Redis recovers.
   - **Purge Contention**: The 15-minute soak spans the five-minute purge cadence; node metrics report which node purged the expired fixtures.

`all` runs the smoke test and the complete baseline suite. `soak` runs the complete baseline suite without the separate smoke test. Both default to a 900-second soak; the calibration uses 5,000 dedicated mailboxes, with three additional fixture mailboxes reserved for expiry and quota checks.

## Usage

### Windows (PowerShell)

```powershell
# Run all tests (smoke, calibration, fencing, quota, soak with failure injections)
.\loadtest\run.ps1

# Run a quick smoke test
.\loadtest\run.ps1 -Mode smoke

# Run calibration only
.\loadtest\run.ps1 -Mode calibrate

# Run the full baseline suite with custom soak parameters
.\loadtest\run.ps1 -Mode soak -Vus 200 -HoldSeconds 900 -SendRate 20
```

### Linux / CI (Bash)

```sh
# Run all tests
./loadtest/run.sh all

# Run smoke test
./loadtest/run.sh smoke

# Run calibration only
./loadtest/run.sh calibrate
```

The runners refuse to overwrite an existing repository `.env` or reuse existing `valence-loadtest` containers/volumes. They remove generated keys, stop the test topology, and retain measurements in a unique temporary directory outside the repository. This directory contains k6 summary exports, periodic Docker resource snapshots, PostgreSQL/Redis observations, per-node metrics, and SIGTERM readiness samples; it does not contain the generated fixture manifest.

`haproxy.cfg` derives simulated `X-Forwarded-For` addresses from `src_port`, allowing one k6 container to exercise multiple client addresses for local WebSocket tests. These are not real client IPs, so this harness does not test production client-IP rate-limit diversity.

The current `/metrics` endpoint does not expose Hikari pending-thread MXBean gauges. The baseline therefore checks for zero PostgreSQL `Lock` waiters, zero deadlocks, and zero Hikari/database connection errors in server logs. Other active PostgreSQL wait events are summarized as informational telemetry.

For sizing future target-scale runs, the measured k6 generator footprint was about 228 MiB average / 258 MiB peak at 1,000 idle VUs, and 1,032 MiB average / 1,172 MiB peak at 5,000 idle VUs, with CPU usage up to about 203% at 5,000 VUs. Docker CPU percentages can exceed 100% when work spans multiple cores.

## Pass / Fail Criteria

- **Zero Lost Messages**: All accepted sends must be delivered to resumed sockets.
- **Zero Sequence Regressions**: `serverSeq` must be strictly increasing per connection.
- **Connection Health**: Zero PostgreSQL `Lock` waiters, zero deadlocks, and zero Hikari/database connection errors in server logs. Non-Lock PostgreSQL wait events are reported but do not fail the run.
- **Error Budget**: Unexpected HTTP 5xx or WebSocket disconnects <= 0.1% (excluding expected 1001 during SIGTERM and 429 during quota saturation).
- **Socket Fencing**: Stale confirms must close old socket with code 4000 and leave unconfirmed messages in queue.
- **Redis Outage**: Messages continue delivery via PostgreSQL polling when Redis is paused; catch-up clears pending checks without thread exhaustion when Redis resumes.
