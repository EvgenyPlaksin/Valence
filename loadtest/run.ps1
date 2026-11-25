param(
    [string]$Mode = "all",
    [int]$Vus = 200,
    [int]$HoldSeconds = 900,
    [int]$SendRate = 20,
    [int]$ProxyPort = 18080,
    [switch]$SkipBuild,
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$validModes = @("smoke", "calibrate", "fencing", "quota", "soak", "all")
if ($Mode -notin $validModes) {
    throw "Mode must be one of: $($validModes -join ', ')"
}
if ($Mode -in @("all", "soak") -and $HoldSeconds -lt 900) {
    throw "Baseline modes require at least 900 seconds of soak"
}

$envPath = Join-Path $repoRoot ".env"
if (Test-Path $envPath) {
    throw "Refusing to overwrite the existing repository .env file"
}

$existingContainers = & docker ps -aq --filter "label=com.docker.compose.project=valence-loadtest"
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect Docker containers"
}
$existingVolumes = & docker volume ls -q --filter "name=valence-loadtest_"
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect Docker volumes"
}
if ($existingContainers -or $existingVolumes) {
    throw "Refusing to reuse or remove existing valence-loadtest Docker resources"
}

function New-UrlSecret {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RNGCryptoServiceProvider]::Create().GetBytes($bytes)
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$tempKeyDir = Join-Path ([System.IO.Path]::GetTempPath()) ("valence-loadtest-" + [System.Guid]::NewGuid().ToString("N"))
$runDir = Join-Path ([System.IO.Path]::GetTempPath()) ("valence-loadtest-run-" + [System.Guid]::NewGuid().ToString("N"))
$envCreated = $false

New-Item -ItemType Directory -Path $tempKeyDir -Force | Out-Null
New-Item -ItemType Directory -Path $runDir -Force | Out-Null

$projectName = "valence-loadtest"
$composeFiles = @("-f", "compose.yaml", "-f", "loadtest/compose.loadtest.yaml")

try {
    Write-Host "Generating test certificate keys in temporary directory..."
    Push-Location (Join-Path $repoRoot "server")
    try {
        & .\gradlew.bat --no-daemon -PkeyOutputDirectory="$tempKeyDir" generateCertificateKeys
        if ($LASTEXITCODE -ne 0) {
            throw "Key generation failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }

    $serverCert = (Get-Content (Join-Path $tempKeyDir "server-certificate.txt") -Raw).Trim()
    $serverKey = (Get-Content (Join-Path $tempKeyDir "server-signing-private.key") -Raw).Trim()

    $challengeKey = New-UrlSecret
    $tokenKey = New-UrlSecret
    $metricsToken = "valence-loadtest-metrics-" + (New-UrlSecret)
    $redisPassword = New-UrlSecret
    $postgresPassword = New-UrlSecret
    $redisPrefix = "valence:loadtest:" + ([System.Guid]::NewGuid().ToString("N").Substring(0, 8))

    $envPath = Join-Path $repoRoot ".env"
    $envContent = @"
POSTGRES_DB=valence_loadtest
POSTGRES_USER=valence
POSTGRES_PASSWORD=$postgresPassword
REDIS_PASSWORD=$redisPassword
VALENCE_REDIS_CHANNEL_PREFIX=$redisPrefix
VALENCE_REDIS_URL=redis://valence:$redisPassword@redis:6379
VALENCE_DATABASE_URL=jdbc:postgresql://db:5432/valence_loadtest
VALENCE_DATABASE_USER=valence
VALENCE_DATABASE_PASSWORD=$postgresPassword
VALENCE_DB_MAX_CONNECTIONS=10
VALENCE_DB_AUTO_MIGRATE=true
VALENCE_TRUST_PROXY_HEADERS=true
VALENCE_METRICS_TOKEN=$metricsToken
VALENCE_CHALLENGE_KEY=$challengeKey
VALENCE_TOKEN_KEY=$tokenKey
VALENCE_SERVER_CERTIFICATE=$serverCert
VALENCE_CERTIFICATE_KEY=$serverKey
JAVA_TOOL_OPTIONS=-Xms128m -Xmx512m -Xss512k -XX:+UseG1GC
PORT=18081
"@
    $envStream = [System.IO.File]::Open($envPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    $envCreated = $true
    try {
        $envWriter = [System.IO.StreamWriter]::new($envStream, [System.Text.UTF8Encoding]::new($false))
        $envWriter.Write($envContent)
    }
    finally {
        if ($envWriter) {
            $envWriter.Dispose()
        }
        else {
            $envStream.Dispose()
        }
    }

    $env:COMPOSE_PROJECT_NAME = $projectName
    $env:POSTGRES_DB = "valence_loadtest"
    $env:POSTGRES_USER = "valence"
    $env:POSTGRES_PASSWORD = $postgresPassword
    $env:REDIS_PASSWORD = $redisPassword
    $env:VALENCE_REDIS_CHANNEL_PREFIX = $redisPrefix
    $env:VALENCE_METRICS_TOKEN = $metricsToken
    $env:VALENCE_CHALLENGE_KEY = $challengeKey
    $env:VALENCE_TOKEN_KEY = $tokenKey
    $env:VALENCE_SERVER_CERTIFICATE = $serverCert
    $env:VALENCE_CERTIFICATE_KEY = $serverKey
    $env:LOADTEST_RUN_DIR = $runDir
    $env:LOADTEST_PROXY_PORT = $ProxyPort.ToString()
    $env:PORT = "18081"
    $env:SERVER_B_PORT = "18082"
    $env:LOADTEST_STATS_PORT = "18404"

    $upArgs = @("compose", "-p", $projectName) + $composeFiles + @("up", "-d")
    if (-not $SkipBuild) {
        $upArgs += "--build"
    }

    Write-Host "Starting Compose topology..."
    Push-Location $repoRoot
    try {
        & docker @upArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Docker compose up failed with exit code $LASTEXITCODE"
        }

        Write-Host "Waiting for HAProxy and nodes to report /ready..."
        $ready = $false
        $lastErr = ""
        for ($i = 0; $i -lt 40; $i++) {
            Start-Sleep -Seconds 2
            try {
                $response = Invoke-WebRequest -Uri "http://127.0.0.1:$ProxyPort/ready" -UseBasicParsing -TimeoutSec 3
                if ($response.StatusCode -eq 200) {
                    $ready = $true
                    break
                }
            }
            catch {
                $lastErr = $_.Exception.Message
            }
        }

        if (-not $ready) {
            Write-Host "Last readiness probe error: $lastErr"
            Write-Host "Dumping container logs before failure..."
            & docker compose -p $projectName @composeFiles logs --tail 50
            throw "Services failed to become ready within timeout"
        }
        Write-Host "Topology is ready."

        $manifestHostPath = Join-Path $runDir "manifest.json"
        $manifestDockerPath = "/run/manifest.json"

        $seedCount = if ($Mode -eq "smoke") { 20 } elseif ($Mode -in @("all", "soak")) { 5003 } elseif ($Mode -eq "calibrate") { 5000 } else { 500 }
        Write-Host "Seeding $seedCount mailboxes..."
        & python loadtest/seed.py --count $seedCount --manifest "$manifestHostPath" --seed-expired 10 --seed-quota 1000
        if ($LASTEXITCODE -ne 0) {
            throw "Seeding failed with exit code $LASTEXITCODE"
        }

        function Invoke-K6Scenario {
            param([string]$ScenarioMode, [int]$VusArg, [int]$HoldSecondsArg, [int]$RateArg)
            $summaryName = "k6_${ScenarioMode}_vus${VusArg}_$(Get-Date -Format 'yyyyMMdd_HHmmss').json"
            $k6Args = @("compose", "-p", $projectName) + $composeFiles + @(
                "run", "--rm",
                "-e", "MODE=$ScenarioMode",
                "-e", "MANIFEST=$manifestDockerPath",
                "-e", "HTTP_URL=http://haproxy:8080",
                "-e", "NODE_A_URL=http://server:8081",
                "-e", "NODE_B_URL=http://server-b:8081",
                "-e", "VALENCE_METRICS_TOKEN=$metricsToken",
                "-e", "VUS=$VusArg",
                "-e", "HOLD_SECONDS=$HoldSecondsArg",
                "-e", "SEND_RATE=$RateArg",
                "k6", "run", "--summary-export", "/run/$summaryName", "/scripts/scenarios.js"
            )
            Write-Host "Running k6 scenario: $ScenarioMode (VUs=$VusArg, Hold=${HoldSecondsArg}s)..."
            $statsPath = Join-Path $runDir "docker_stats_${ScenarioMode}_vus${VusArg}.txt"
            $statsJob = Start-Job -ArgumentList $statsPath -ScriptBlock {
                param($path)
                while ($true) {
                    Add-Content -Path $path -Value ("sampled_at=" + (Get-Date).ToString("o"))
                    & docker stats --no-stream --format "table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}`t{{.PIDs}}" |
                        Add-Content -Path $path
                    if ($LASTEXITCODE -ne 0) {
                        throw "Docker stats sampling failed"
                    }
                    Start-Sleep -Seconds 2
                }
            }
            $k6ExitCode = 1
            try {
                & docker @k6Args
                $k6ExitCode = $LASTEXITCODE
            }
            finally {
                Stop-Job $statsJob -ErrorAction SilentlyContinue
                Receive-Job $statsJob -ErrorAction Continue | Out-Null
                $statsErrors = $statsJob.ChildJobs[0].Error.Count
                Remove-Job $statsJob -ErrorAction SilentlyContinue
            }
            if ($statsErrors -gt 0) {
                throw "Docker stats collection failed during $ScenarioMode"
            }
            if ($k6ExitCode -ne 0) {
                throw "k6 scenario $ScenarioMode failed with exit code $k6ExitCode"
            }
            $summaryPath = Join-Path $runDir $summaryName
            if (-not (Test-Path $summaryPath)) {
                throw "k6 scenario $ScenarioMode did not produce a summary"
            }
            $scenarioSummary = Get-Content $summaryPath -Raw | ConvertFrom-Json
            if (-not $scenarioSummary.metrics.checks -or $scenarioSummary.metrics.checks.fails -gt 0) {
                throw "k6 scenario $ScenarioMode had failed checks"
            }
        }

        function Start-K6Scenario {
            param([string]$ScenarioMode, [int]$VusArg, [int]$HoldSecondsArg, [int]$RateArg)
            $summaryName = "k6_${ScenarioMode}_vus${VusArg}_$(Get-Date -Format 'yyyyMMdd_HHmmss').json"
            $logPath = Join-Path $runDir "k6_${ScenarioMode}.log"
            $dockerArgs = @("compose", "-p", $projectName) + $composeFiles + @(
                "run", "--rm",
                "-e", "MODE=$ScenarioMode",
                "-e", "MANIFEST=$manifestDockerPath",
                "-e", "HTTP_URL=http://haproxy:8080",
                "-e", "NODE_A_URL=http://server:8081",
                "-e", "NODE_B_URL=http://server-b:8081",
                "-e", "VALENCE_METRICS_TOKEN=$metricsToken",
                "-e", "VUS=$VusArg",
                "-e", "HOLD_SECONDS=$HoldSecondsArg",
                "-e", "SEND_RATE=$RateArg",
                "k6", "run", "--summary-export", "/run/$summaryName", "/scripts/scenarios.js"
            )
            $dockerArgsJson = ConvertTo-Json -InputObject $dockerArgs -Compress
            Start-Job -ArgumentList $dockerArgsJson, $repoRoot, $logPath -ScriptBlock {
                param($argsJson, $root, $outputPath)
                $arguments = @(ConvertFrom-Json $argsJson)
                Push-Location $root
                try {
                    & docker @arguments *> $outputPath
                    if ($LASTEXITCODE -ne 0) {
                        throw "k6 scenario failed with exit code $LASTEXITCODE"
                    }
                }
                finally {
                    Pop-Location
                }
            }
        }

        function Wait-ForRedisRecovery {
            $deadline = (Get-Date).AddSeconds(30)
            while ((Get-Date) -lt $deadline) {
                try {
                    $headers = @{ Authorization = "Bearer $metricsToken" }
                    $nodeAMetrics = (Invoke-WebRequest -Uri "http://127.0.0.1:18081/metrics" -Headers $headers -UseBasicParsing -TimeoutSec 2).Content
                    $nodeBMetrics = (Invoke-WebRequest -Uri "http://127.0.0.1:18082/metrics" -Headers $headers -UseBasicParsing -TimeoutSec 2).Content
                    if ($nodeAMetrics -match 'valence_redis_subscriber_connected\s+1' -and
                        $nodeBMetrics -match 'valence_redis_subscriber_connected\s+1') {
                        return
                    }
                }
                catch {}
                Start-Sleep -Seconds 1
            }
            throw "Redis subscribers did not recover on both nodes"
        }

        function Wait-ForNodeAReady {
            $deadline = (Get-Date).AddSeconds(90)
            while ((Get-Date) -lt $deadline) {
                try {
                    $ready = Invoke-WebRequest -Uri "http://127.0.0.1:18081/ready" -UseBasicParsing -TimeoutSec 2
                    if ($ready.StatusCode -eq 200) {
                        return
                    }
                }
                catch {}
                Start-Sleep -Seconds 1
            }
            throw "Node A did not become ready after the SIGTERM restart"
        }

        function Get-MetricValue {
            param([string]$Metrics, [string]$Name)
            $pattern = "(?m)^" + [regex]::Escape($Name) + "\s+([0-9]+)$"
            $match = [regex]::Match($Metrics, $pattern)
            if (-not $match.Success) {
                return 0
            }
            return [long]$match.Groups[1].Value
        }

        if ($Mode -eq "smoke" -or $Mode -eq "all") {
            Write-Host "=== Running Smoke Test ==="
            Invoke-K6Scenario -ScenarioMode "smoke" -VusArg 10 -HoldSecondsArg 20 -RateArg 5
        }

        if ($Mode -eq "quota" -or $Mode -eq "all" -or $Mode -eq "soak") {
            Write-Host "=== Running Slow Consumer & Quota Test ==="
            Invoke-K6Scenario -ScenarioMode "quota" -VusArg 1 -HoldSecondsArg 30 -RateArg 0
        }

        if ($Mode -eq "calibrate" -or $Mode -eq "all" -or $Mode -eq "soak") {
            Write-Host "=== Running k6 Calibration: 1,000 idle sockets ==="
            Invoke-K6Scenario -ScenarioMode "calibrate" -VusArg 1000 -HoldSecondsArg 45 -RateArg 0

            Write-Host "=== Running k6 Calibration: 5,000 idle sockets ==="
            Invoke-K6Scenario -ScenarioMode "calibrate" -VusArg 5000 -HoldSecondsArg 45 -RateArg 0
        }

        if ($Mode -eq "fencing" -or $Mode -eq "all" -or $Mode -eq "soak") {
            Write-Host "=== Running Stale-Socket Fencing Test with Redis down ==="
            & docker compose -p $projectName @composeFiles pause redis
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to pause Redis for the fencing test"
            }
            Invoke-K6Scenario -ScenarioMode "fencing-down" -VusArg 1 -HoldSecondsArg 10 -RateArg 0
            & docker compose -p $projectName @composeFiles unpause redis
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to resume Redis after the fencing test"
            }
            Wait-ForRedisRecovery
            Write-Host "=== Running Stale-Socket Fencing Test with Redis up ==="
            Invoke-K6Scenario -ScenarioMode "fencing-up" -VusArg 1 -HoldSecondsArg 10 -RateArg 0
        }

        if ($Mode -eq "soak" -or $Mode -eq "all") {
            Write-Host "=== Running Baseline Soak Test ==="
            $soakHold = $HoldSeconds

            $telemetryJob = Start-Job -ScriptBlock {
                param($proj, $files, $dir, $token)
                $ErrorActionPreference = "Stop"
                $loop = 0
                while ($true) {
                    Start-Sleep -Seconds 20
                    $loop++
                    $ts = (Get-Date).ToString("yyyyMMdd_HHmmss")
                    & docker compose -p $proj @files exec -T db sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB" -f /scripts/postgres-observe.sql' > (Join-Path $dir "db_stat_$ts.txt") 2>&1
                    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL sampling failed" }
                    & docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.PIDs}}" > (Join-Path $dir "docker_stats_$ts.txt") 2>&1
                    if ($LASTEXITCODE -ne 0) { throw "Docker stats sampling failed" }
                    & docker compose -p $proj @files exec -T redis sh -c 'redis-cli --user valence -a "$REDIS_PASSWORD" info' > (Join-Path $dir "redis_info_$ts.txt") 2>&1
                    if ($LASTEXITCODE -ne 0) { throw "Redis sampling failed" }
                    $headers = @{ Authorization = "Bearer $token" }
                    (Invoke-WebRequest -Uri "http://127.0.0.1:18081/metrics" -Headers $headers -UseBasicParsing).Content | Set-Content (Join-Path $dir "node_a_metrics_$ts.txt")
                    (Invoke-WebRequest -Uri "http://127.0.0.1:18082/metrics" -Headers $headers -UseBasicParsing).Content | Set-Content (Join-Path $dir "node_b_metrics_$ts.txt")
                    & docker compose -p $proj @files exec -T server sh -c 'printf "node_a_fd_count="; ls /proc/1/fd | wc -l' > (Join-Path $dir "node_a_fd_$ts.txt") 2>&1
                    if ($LASTEXITCODE -ne 0) { throw "Node A file descriptor sampling failed" }
                    & docker compose -p $proj @files exec -T server-b sh -c 'printf "node_b_fd_count="; ls /proc/1/fd | wc -l' > (Join-Path $dir "node_b_fd_$ts.txt") 2>&1
                    if ($LASTEXITCODE -ne 0) { throw "Node B file descriptor sampling failed" }
                }
            } -ArgumentList $projectName, $composeFiles, $runDir, $metricsToken

            $telemetryErrors = 0
            try {
                Invoke-K6Scenario -ScenarioMode "soak" -VusArg $Vus -HoldSecondsArg $soakHold -RateArg $SendRate
            }
            finally {
                Stop-Job $telemetryJob -ErrorAction SilentlyContinue
                Receive-Job $telemetryJob -ErrorAction Continue | Out-Null
                $telemetryErrors = $telemetryJob.ChildJobs[0].Error.Count
                Remove-Job $telemetryJob -ErrorAction SilentlyContinue
            }
            if ($telemetryErrors -gt 0) {
                throw "Telemetry sampling failed during soak"
            }
            $soakSummaryPath = Get-ChildItem $runDir -Filter "k6_soak_vus*.json" | Sort-Object LastWriteTime | Select-Object -Last 1 -ExpandProperty FullName
            if (-not $soakSummaryPath) {
                throw "The soak k6 summary was not written"
            }
            $soakMetrics = (Get-Content $soakSummaryPath -Raw | ConvertFrom-Json).metrics
            $acceptedCount = if ($soakMetrics.loadtest_accepted_messages) { $soakMetrics.loadtest_accepted_messages.count } else { 0 }
            $deliveredCount = if ($soakMetrics.loadtest_delivered_messages) { $soakMetrics.loadtest_delivered_messages.count } else { 0 }
            $sequenceErrorCount = if ($soakMetrics.loadtest_sequence_errors) { $soakMetrics.loadtest_sequence_errors.count } else { 0 }
            $frameErrorCount = if ($soakMetrics.loadtest_frame_errors) { $soakMetrics.loadtest_frame_errors.count } else { 0 }
            if ($acceptedCount -ne $deliveredCount -or $sequenceErrorCount -ne 0 -or $frameErrorCount -ne 0) {
                throw "Soak replay invariants failed: accepted=$acceptedCount delivered=$deliveredCount sequenceErrors=$sequenceErrorCount frameErrors=$frameErrorCount"
            }
            $sendLatency = $soakMetrics.loadtest_send_latency_ms
            $deliveryLatency = $soakMetrics.loadtest_delivery_latency_ms
            Write-Host "Soak accepted=$acceptedCount delivered=$deliveredCount sequenceErrors=$sequenceErrorCount frameErrors=$frameErrorCount"
            Write-Host "Send latency p95=$($sendLatency.'p(95)')ms p99=$($sendLatency.'p(99)')ms; delivery latency p95=$($deliveryLatency.'p(95)')ms p99=$($deliveryLatency.'p(99)')ms"
            $soakMetricHeader = @{ Authorization = "Bearer $metricsToken" }
            $nodeAMetricsDuringSoak = (Invoke-WebRequest -Uri "http://127.0.0.1:18081/metrics" -Headers $soakMetricHeader -UseBasicParsing).Content
            $nodeBMetricsDuringSoak = (Invoke-WebRequest -Uri "http://127.0.0.1:18082/metrics" -Headers $soakMetricHeader -UseBasicParsing).Content
            $nodeAMetricsDuringSoak | Set-Content (Join-Path $runDir "node_a_metrics_soak_final.txt")
            $nodeBMetricsDuringSoak | Set-Content (Join-Path $runDir "node_b_metrics_soak_final.txt")

            Write-Host "=== Testing SIGTERM Drain Window ==="
            $nodeAReady = Invoke-WebRequest -Uri "http://127.0.0.1:18081/ready" -UseBasicParsing -TimeoutSec 3
            if ($nodeAReady.StatusCode -ne 200) {
                throw "Node A was not ready before the SIGTERM test"
            }
            $drainJob = Start-K6Scenario -ScenarioMode "drain" -VusArg 1 -HoldSecondsArg 60 -RateArg 0
            $socketReady = $false
            for ($k = 0; $k -lt 40; $k++) {
                try {
                    $metricResponse = Invoke-WebRequest -Uri "http://127.0.0.1:18081/metrics" -Headers @{ Authorization = "Bearer $metricsToken" } -UseBasicParsing -TimeoutSec 1
                    if ($metricResponse.Content -match 'valence_active_connections(?:\{[^}]*\})?\s+[1-9]\d*') {
                        $socketReady = $true
                        break
                    }
                }
                catch {}
                Start-Sleep -Milliseconds 500
            }
            if (-not $socketReady) {
                Stop-Job $drainJob -ErrorAction SilentlyContinue
                Remove-Job $drainJob -ErrorAction SilentlyContinue
                throw "Drain WebSocket did not connect to node A"
            }
            & docker compose -p $projectName @composeFiles kill -s SIGTERM server
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to send SIGTERM to node A"
            }
            $readySamples = @("sampled_at,status")
            for ($k = 0; $k -lt 50; $k++) {
                $statusCode = 0
                try {
                    $readyResponse = Invoke-WebRequest -Uri "http://127.0.0.1:18081/ready" -UseBasicParsing -TimeoutSec 1
                    $statusCode = $readyResponse.StatusCode
                }
                catch {
                    if ($_.Exception.Response) {
                        $statusCode = [int]$_.Exception.Response.StatusCode
                    }
                }
                $readySamples += "$(Get-Date -Format o),$statusCode"
                Start-Sleep -Milliseconds 200
            }
            $readySamples | Set-Content (Join-Path $runDir "sigterm_ready_window.csv")
            $ready200Count = @($readySamples | Select-String ",200$").Count
            Write-Host "Node A /ready samples during SIGTERM: 200=$ready200Count total=$($readySamples.Count - 1); drain WebSocket must close with 1001"
            Wait-Job $drainJob -Timeout 90 | Out-Null
            if ($drainJob.State -ne "Completed") {
                Stop-Job $drainJob -ErrorAction SilentlyContinue
                Remove-Job $drainJob -ErrorAction SilentlyContinue
                throw "Drain k6 scenario did not finish"
            }
            Receive-Job $drainJob -ErrorAction Stop | Out-Null
            Remove-Job $drainJob
            $drainSummaryPath = Get-ChildItem $runDir -Filter "k6_drain_vus*.json" | Sort-Object LastWriteTime | Select-Object -Last 1 -ExpandProperty FullName
            if (-not $drainSummaryPath) {
                throw "The drain k6 summary was not written"
            }
            $drainSummary = Get-Content $drainSummaryPath -Raw | ConvertFrom-Json
            if (-not $drainSummary.metrics.checks -or $drainSummary.metrics.checks.fails -gt 0) {
                throw "The SIGTERM drain test had failed checks"
            }
            & docker compose -p $projectName @composeFiles up -d server
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to restart node A after SIGTERM test"
            }
            Wait-ForNodeAReady

            Write-Host "=== Testing Redis Outage Recovery ==="
            & docker compose -p $projectName @composeFiles pause redis
            Start-Sleep -Seconds 3
            $proxyStillReady = (Invoke-WebRequest -Uri "http://127.0.0.1:$ProxyPort/ready" -UseBasicParsing).StatusCode -eq 200
            Write-Host "HAProxy /ready returns 200 with Redis paused: $proxyStillReady"
            if (-not $proxyStillReady) {
                throw "Readiness failed while Redis was paused"
            }
            Invoke-K6Scenario -ScenarioMode "redis-down" -VusArg 1 -HoldSecondsArg 55 -RateArg 0
            & docker compose -p $projectName @composeFiles unpause redis
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to resume Redis after the outage test"
            }
            Wait-ForRedisRecovery
            Invoke-K6Scenario -ScenarioMode "redis-up" -VusArg 1 -HoldSecondsArg 10 -RateArg 0
        }

        Write-Host "Capturing final telemetry snapshots..."
        & docker compose -p $projectName @composeFiles exec -T db sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB" -f /scripts/postgres-observe.sql' > (Join-Path $runDir "final_postgres_observe.txt")
        if ($LASTEXITCODE -ne 0) {
            throw "Final PostgreSQL observation failed"
        }
        & docker compose -p $projectName @composeFiles exec -T redis sh -c 'redis-cli --user valence -a "$REDIS_PASSWORD" info' > (Join-Path $runDir "final_redis_info.txt")
        if ($LASTEXITCODE -ne 0) {
            throw "Final Redis observation failed"
        }
        & docker stats --no-stream --format "table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}`t{{.PIDs}}" > (Join-Path $runDir "docker_stats_final.txt")
        & docker compose -p $projectName @composeFiles exec -T server sh -c 'printf "node_a_fd_count="; ls /proc/1/fd | wc -l' > (Join-Path $runDir "node_a_fd_final.txt")
        & docker compose -p $projectName @composeFiles exec -T server-b sh -c 'printf "node_b_fd_count="; ls /proc/1/fd | wc -l' > (Join-Path $runDir "node_b_fd_final.txt")
        $serverLogs = & docker compose -p $projectName @composeFiles logs --no-color server server-b 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to inspect server logs"
        }
        $connectionErrors = @($serverLogs | Select-String -Pattern "HikariPool.*(ERROR|WARN)|Connection is not available|SQLTransientConnectionException|too many clients|remaining connection slots|Failed to validate connection")
        "hikari_or_database_connection_error_lines=$($connectionErrors.Count)" | Set-Content (Join-Path $runDir "database_connection_errors.txt")
        if ($connectionErrors.Count -gt 0) {
            throw "Hikari or database connection errors were recorded"
        }
        $dbFiles = @(Get-ChildItem $runDir -Filter "db_stat_*.txt") + @((Get-Item (Join-Path $runDir "final_postgres_observe.txt")))
        $dbWaitSamples = foreach ($file in $dbFiles) {
            $row = Get-Content $file.FullName | Where-Object { $_ -match '^\s*20\d{2}-\d{2}-\d{2} .*\|' } | Select-Object -First 1
            if (-not $row) {
                throw "PostgreSQL wait sample missing in $($file.Name)"
            }
            $columns = $row.Split("|")
            if ($columns.Count -lt 4) {
                throw "PostgreSQL wait sample malformed in $($file.Name)"
            }
            [pscustomobject]@{
                Waiters = [int]$columns[2].Trim()
                LockWaiters = [int]$columns[3].Trim()
            }
        }
        $maxWaiters = ($dbWaitSamples | Measure-Object -Property Waiters -Maximum).Maximum
        $maxLockWaiters = ($dbWaitSamples | Measure-Object -Property LockWaiters -Maximum).Maximum
        "max_database_waiters=$maxWaiters max_lock_waiters=$maxLockWaiters" | Set-Content (Join-Path $runDir "database_waiter_summary.txt")
        Write-Host "Database waiters max=$maxWaiters lockWaiters max=$maxLockWaiters; connection error log lines=$($connectionErrors.Count)"
        if ($maxWaiters -ne 0 -or $maxLockWaiters -ne 0) {
            throw "PostgreSQL connection or lock waiters were observed"
        }

        $metricHeader = @{ Authorization = "Bearer $metricsToken" }
        $nodeAMetrics = (Invoke-WebRequest -Uri "http://127.0.0.1:18081/metrics" -Headers $metricHeader -UseBasicParsing).Content
        $nodeBMetrics = (Invoke-WebRequest -Uri "http://127.0.0.1:18082/metrics" -Headers $metricHeader -UseBasicParsing).Content
        $nodeAMetrics | Set-Content (Join-Path $runDir "node_a_metrics.txt")
        $nodeBMetrics | Set-Content (Join-Path $runDir "node_b_metrics.txt")
        if ($Mode -in @("all", "soak")) {
            $purgedA = Get-MetricValue -Metrics $nodeAMetricsDuringSoak -Name "valence_messages_purged_total"
            $purgedB = Get-MetricValue -Metrics $nodeBMetricsDuringSoak -Name "valence_messages_purged_total"
            if (($purgedA + $purgedB) -ne 10 -or ($purgedA -gt 0 -and $purgedB -gt 0)) {
                throw "Purge advisory-lock check failed: nodeA=$purgedA nodeB=$purgedB expected a single winner purging 10 fixtures"
            }
            Write-Host "Purge lock winner: nodeA=$purgedA nodeB=$purgedB"
        }
        $manifestHostPath | Remove-Item -Force

        Write-Host "All loadtest phases completed successfully."
        Write-Host "Telemetry artifacts saved to: $runDir"
    }
    finally {
        Pop-Location
        if (-not $KeepRunning) {
            Write-Host "Tearing down Compose topology..."
            Push-Location $repoRoot
            try {
                & docker compose -p $projectName @composeFiles down -v
            }
            finally {
                Pop-Location
            }
        }
    }
}
finally {
    if ($envCreated -and (Test-Path $envPath)) {
        Remove-Item -Path $envPath -Force -ErrorAction SilentlyContinue
    }
    $manifestPath = Join-Path $runDir "manifest.json"
    if (Test-Path $manifestPath) {
        Remove-Item -Path $manifestPath -Force -ErrorAction SilentlyContinue
    }
    Write-Host "Cleaning up temporary key directory..."
    Remove-Item -Path $tempKeyDir -Recurse -Force -ErrorAction SilentlyContinue
}
