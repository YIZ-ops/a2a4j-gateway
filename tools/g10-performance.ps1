[CmdletBinding()]
param(
    [int]$SequentialRequests = 80,
    [int]$ConcurrentRequests = 40,
    [int]$Concurrency = 8,
    [int]$AgentPortA = 18091,
    [int]$AgentPortB = 18092,
    [int]$GatewayPort = 18099,
    [string]$ReportPath = ""
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gatewayJar = Join-Path $root 'a2a4j-samples/gateway-hello-world/target/gateway-hello-world-0.0.1-exec.jar'
$agentJar = Join-Path $root 'a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar'
if (-not (Test-Path $gatewayJar) -or -not (Test-Path $agentJar)) {
    Push-Location $root
    try {
        & (Join-Path $root 'mvnw.cmd') -q package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'sample package failed' }
    }
    finally { Pop-Location }
}
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $root 'docs/agent-gateway/mvp-performance-2026-08-01.md'
}

$apiKey = 'g10-' + [guid]::NewGuid().ToString('N')
$env:A2A_SAMPLE_API_KEY = $apiKey
$logRoot = Join-Path ([IO.Path]::GetTempPath()) ('a2a4j-g10-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$processes = @()

function Stop-SamplePorts {
    $owners = Get-NetTCPConnection -LocalPort @($AgentPortA, $AgentPortB, $GatewayPort) -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($owner in $owners) {
        if ($owner -and $owner -ne $PID) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue }
    }
}
if (-not (Test-Path $gatewayJar)) {
    $gatewayJar = Join-Path $root 'a2a4j-samples/gateway-hello-world/target/gateway-hello-world-0.0.1.jar'
}
if (-not (Test-Path $gatewayJar) -or -not (Test-Path $agentJar)) {
    throw 'gateway or server-hello-world executable jar was not found after package'
}

function Assert-PortAvailable([int]$Port) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "performance port $Port is already in use; choose another *Port parameter"
    }
}

function Wait-Http {
    param([string]$Url)
    for ($i = 0; $i -lt 100; $i++) {
        try {
            $response = Invoke-RestMethod -Uri $Url -TimeoutSec 2
            if ($null -ne $response) { return }
        }
        catch { }
        Start-Sleep -Milliseconds 300
    }
    throw "endpoint did not become ready: $Url"
}

if (-not ('GatewayPerfProbe' -as [type])) {
    Add-Type -AssemblyName System.Net.Http
    $httpAssembly = [System.Net.Http.HttpClient].Assembly.Location
    Add-Type -ReferencedAssemblies @($httpAssembly) -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;

public static class GatewayPerfProbe {
    public static long[] Run(string url, string apiKey, string payload, int requests, int concurrency) {
        using (var client = new HttpClient()) {
            client.Timeout = TimeSpan.FromSeconds(15);
            var values = new long[requests];
            for (var offset = 0; offset < requests; offset += concurrency) {
                var count = Math.Min(concurrency, requests - offset);
                var tasks = new Task<long>[count];
                for (var i = 0; i < count; i++) {
                    var index = offset + i;
                    tasks[i] = Task.Run(async () => {
                        var watch = Stopwatch.StartNew();
                        using (var request = new HttpRequestMessage(HttpMethod.Post, url)) {
                            if (!String.IsNullOrWhiteSpace(apiKey)) {
                                request.Headers.Add("X-A2A-API-Key", apiKey);
                            }
                            request.Headers.Add("A2A-Version", "1.0");
                            request.Headers.Add("X-A2A-Target-Agent", "echo-a");
                            request.Content = new StringContent(payload, Encoding.UTF8, "application/a2a+json");
                            using (var response = await client.SendAsync(request)) {
                                var body = await response.Content.ReadAsStringAsync();
                                if (!response.IsSuccessStatusCode || String.IsNullOrWhiteSpace(body)) {
                                    throw new InvalidOperationException("HTTP " + (int)response.StatusCode + " empty/error body");
                                }
                            }
                        }
                        watch.Stop();
                        return watch.ElapsedMilliseconds;
                    });
                }
                Task.WaitAll(tasks);
                for (var i = 0; i < count; i++) { values[offset + i] = tasks[i].Result; }
            }
            return values;
        }
    }
}
'@
}

function Percentile([long[]]$Values, [double]$Probability) {
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($Probability * $sorted.Count) - 1)
    return [int64]$sorted[$index]
}

try {
    Assert-PortAvailable $AgentPortA
    Assert-PortAvailable $AgentPortB
    Assert-PortAvailable $GatewayPort
    $processes += Start-Process java -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'agent-a.out') `
        -RedirectStandardError (Join-Path $logRoot 'agent-a.err') -ArgumentList @('-jar', $agentJar,
            '--spring.profiles.active=echo-a', "--server.port=$AgentPortA")
    $processes += Start-Process java -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'agent-b.out') `
        -RedirectStandardError (Join-Path $logRoot 'agent-b.err') -ArgumentList @('-jar', $agentJar,
            '--spring.profiles.active=echo-b', "--server.port=$AgentPortB")
    Wait-Http "http://127.0.0.1:$AgentPortA/.well-known/agent.json"
    Wait-Http "http://127.0.0.1:$AgentPortB/.well-known/agent.json"
    $processes += Start-Process java -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'gateway.out') `
        -RedirectStandardError (Join-Path $logRoot 'gateway.err') -ArgumentList @('-jar', $gatewayJar,
            "--server.port=$GatewayPort", '--a2a.gateway.refresh-interval=1s',
            '--a2a.gateway.agents[0].tenant-id=demo', '--a2a.gateway.agents[0].agent-id=echo-a',
            '--a2a.gateway.agents[0].display-name=Echo Agent A',
            '--a2a.gateway.agents[0].instances[0].instance-id=perf-a',
            "--a2a.gateway.agents[0].instances[0].card-url=http://127.0.0.1:$AgentPortA/.well-known/agent.json",
            '--a2a.gateway.agents[1].tenant-id=demo', '--a2a.gateway.agents[1].agent-id=echo-b',
            '--a2a.gateway.agents[1].display-name=Echo Agent B',
            '--a2a.gateway.agents[1].instances[0].instance-id=perf-b',
            "--a2a.gateway.agents[1].instances[0].card-url=http://127.0.0.1:$AgentPortB/.well-known/agent.json")
    Wait-Http "http://127.0.0.1:$GatewayPort/actuator/health"
    Start-Sleep -Seconds 2

    $payload = '{"message":{"role":"ROLE_USER","parts":[{"text":"g10-performance"}]}}'
    $gatewayLatencies = [GatewayPerfProbe]::Run("http://127.0.0.1:$GatewayPort/message:send", $apiKey, $payload,
        $SequentialRequests, 1)
    $concurrentLatencies = [GatewayPerfProbe]::Run("http://127.0.0.1:$GatewayPort/message:send", $apiKey, $payload,
        $ConcurrentRequests, $Concurrency)
    $upstreamPayload = '{"jsonrpc":"2.0","id":"g10","method":"SendMessage","params":{"message":{"role":"ROLE_USER","parts":[{"kind":"text","text":"g10-upstream"}]}}}'
    $upstreamLatencies = [GatewayPerfProbe]::Run("http://127.0.0.1:$AgentPortA/a2a/server", '', $upstreamPayload,
        $SequentialRequests, 1)

    $environment = (Get-CimInstance Win32_OperatingSystem).Caption + '; ' + (Get-CimInstance Win32_ComputerSystem).Model
    $savedErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $jvmLines = @(& java -version 2>&1 | ForEach-Object { $_.ToString() } |
        Where-Object { $_ -match '^(java version|Java\(TM\)|Java HotSpot)' })
    $jvm = ($jvmLines -join ' ').Trim()
    $ErrorActionPreference = $savedErrorAction
    $gatewayP95 = Percentile $gatewayLatencies 0.95
    $gatewayP99 = Percentile $gatewayLatencies 0.99
    $concurrentP95 = Percentile $concurrentLatencies 0.95
    $concurrentP99 = Percentile $concurrentLatencies 0.99
    $upstreamP95 = Percentile $upstreamLatencies 0.95
    $upstreamP99 = Percentile $upstreamLatencies 0.99
    $overhead = [Math]::Max(0, $gatewayP95 - $upstreamP95)
    $reportLines = @(
        '# MVP non-streaming performance baseline (2026-08-01)',
        '',
        'Generated by `tools/g10-performance.ps1`. It starts two independent `server-hello-world` Agent processes (echo-a/echo-b profiles) and one Gateway, sends a fixed HTTP+JSON payload, and validates every response as HTTP 2xx with a non-empty body.',
        '',
        '| Item | Value |',
        '| --- | --- |',
        "| OS/hardware | $environment |",
        "| JVM | $jvm |",
        "| Payload | $($payload.Length) bytes |",
        "| Sequential requests | $SequentialRequests |",
        "| Concurrent requests | $ConcurrentRequests |",
        "| Concurrency | $Concurrency |",
        "| Gateway sequential p95/p99 | ${gatewayP95}ms / ${gatewayP99}ms |",
        "| Gateway concurrent p95/p99 | ${concurrentP95}ms / ${concurrentP99}ms |",
        "| Direct upstream p95/p99 | ${upstreamP95}ms / ${upstreamP99}ms |",
        "| Gateway overhead (sequential p95) | ${overhead}ms (local loopback estimate) |",
        '| SSE baseline | `GatewayHttpJsonDataPlaneE2eTest.sustainsTwoHundredConcurrentSseStreamsWithinTenantQuota` |',
        '',
        'This is a repeatable Windows developer-machine baseline, not a capacity commitment. Re-run it on target hardware and representative upstream latency before production release, preserving request count, concurrency, and payload with the result.'
    )
    New-Item -ItemType Directory -Path (Split-Path -Parent $ReportPath) -Force | Out-Null
    Set-Content -LiteralPath $ReportPath -Value ($reportLines -join [Environment]::NewLine) -Encoding UTF8
    Get-Content -LiteralPath $ReportPath
}
finally {
    foreach ($process in $processes) {
        if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    Remove-Item -LiteralPath $logRoot -Recurse -Force -ErrorAction SilentlyContinue
}
