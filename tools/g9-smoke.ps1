param(
    [int]$AgentPortA = 8091,
    [int]$AgentPortB = 8092,
    [int]$GatewayPort = 8099,
    [switch]$ForceCleanup
)

$gatewayJarCandidates = @(
    (Join-Path (Get-Location) 'a2a4j-samples/gateway-hello-world/target/gateway-hello-world-0.0.1-exec.jar'),
    (Join-Path (Get-Location) 'a2a4j-samples/gateway-hello-world/target/gateway-hello-world-0.0.1.jar')
)
$gatewayJar = $gatewayJarCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
$agentJarCandidates = @(
    (Join-Path (Get-Location) 'a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar')
)
$agentJar = $agentJarCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($gatewayJar) -or [string]::IsNullOrWhiteSpace($agentJar)) {
    throw 'gateway and server-hello-world executable jars were not found; run mvnw package -DskipTests first'
}
$agentUrlA = "http://127.0.0.1:$AgentPortA"
$agentUrlB = "http://127.0.0.1:$AgentPortB"
$gatewayUrl = "http://127.0.0.1:$GatewayPort"
$env:A2A_SAMPLE_API_KEY = 'g9-smoke-' + [guid]::NewGuid().ToString('N')
$processes = @()
$logRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('a2a4j-g9-smoke-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null

function Stop-SamplePorts {
    param([int[]]$Ports)
    $owners = Get-NetTCPConnection -LocalPort $Ports -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($owner in $owners) {
        if ($owner -and $owner -ne $PID) {
            Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue
        }
    }
}

try {
    if ($ForceCleanup) { Stop-SamplePorts @($AgentPortA, $AgentPortB, $GatewayPort) }
    $processes += Start-Process java -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'agent-a.out') -RedirectStandardError (Join-Path $logRoot 'agent-a.err') -ArgumentList @('-jar', $agentJar,
        '--spring.profiles.active=echo-a', "--server.port=$AgentPortA")
    $processes += Start-Process java -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'agent-b.out') -RedirectStandardError (Join-Path $logRoot 'agent-b.err') -ArgumentList @('-jar', $agentJar,
        '--spring.profiles.active=echo-b', "--server.port=$AgentPortB")
    $agentsReady = $false
    for ($i = 0; $i -lt 100; $i++) {
        if ($processes[0].HasExited -or $processes[1].HasExited) {
            throw 'server-hello-world demo Agent exited before its Card became available'
        }
        try {
            $cardA = Invoke-RestMethod "$agentUrlA/.well-known/agent-card.json" -TimeoutSec 2
            $cardB = Invoke-RestMethod "$agentUrlB/.well-known/agent-card.json" -TimeoutSec 2
            if ($cardA.id -eq 'echo-a' -and $cardB.id -eq 'echo-b') {
                $agentsReady = $true
                break
            }
        }
        catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $agentsReady) {
        Get-ChildItem $logRoot -File | ForEach-Object {
            Write-Output "--- $($_.Name) ---"
            Get-Content $_.FullName -ErrorAction SilentlyContinue
        }
        throw 'sample processes did not become ready'
    }
    $cardASkillIds = @($cardA.skills | ForEach-Object { $_.id })
    $cardBSkillIds = @($cardB.skills | ForEach-Object { $_.id })
    if ($cardASkillIds.Count -ne 2 -or $cardASkillIds -notcontains 'hello-world' -or
        $cardASkillIds -notcontains 'code-generation' -or $cardBSkillIds.Count -ne 1 -or
        $cardBSkillIds[0] -ne 'task-summary') {
        throw "sample Skill profiles are unexpected: echo-a=$($cardASkillIds -join ',') echo-b=$($cardBSkillIds -join ',')"
    }
    Write-Output "agent-skills echo-a=$($cardASkillIds -join ',') echo-b=$($cardBSkillIds -join ',')"
    $processes += Start-Process java -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'gateway.out') -RedirectStandardError (Join-Path $logRoot 'gateway.err') -ArgumentList @('-jar', $gatewayJar,
        "--server.port=$GatewayPort", '--a2a.gateway.refresh-interval=1s',
        '--a2a.gateway.agents[0].tenant-id=demo', '--a2a.gateway.agents[0].agent-id=echo-a',
        '--a2a.gateway.agents[0].display-name=Echo Agent A', '--a2a.gateway.agents[0].instances[0].instance-id=smoke-a',
        "--a2a.gateway.agents[0].instances[0].card-url=$agentUrlA/.well-known/agent-card.json",
        '--a2a.gateway.agents[1].tenant-id=demo', '--a2a.gateway.agents[1].agent-id=echo-b',
        '--a2a.gateway.agents[1].display-name=Echo Agent B', '--a2a.gateway.agents[1].instances[0].instance-id=smoke-b',
        "--a2a.gateway.agents[1].instances[0].card-url=$agentUrlB/.well-known/agent-card.json")
    $gatewayReady = $false
    for ($i = 0; $i -lt 100; $i++) {
        try {
            $health = Invoke-RestMethod "$gatewayUrl/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') {
                $gatewayReady = $true
                break
            }
        }
        catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $gatewayReady) {
        throw 'gateway did not become ready'
    }
    Write-Output ("gateway health=" + ($health | ConvertTo-Json -Compress))
    $probeBody = Join-Path $logRoot 'gateway-probe.json'
    Set-Content -LiteralPath $probeBody -Value '{"message":{"role":"ROLE_USER","parts":[{"text":"probe"}]}}' -NoNewline
    $probeOutput = Join-Path $logRoot 'gateway-probe.out'
    $unauthHeaderFile = Join-Path $logRoot 'gateway-unauth.headers'
    $unauthStatus = (& curl.exe -sS --max-time 10 -o $probeOutput -w '%{http_code}' -X POST `
        -D $unauthHeaderFile `
        -H 'A2A-Version: 1.0' -H 'Content-Type: application/a2a+json' --data-binary "@$probeBody" `
        "$gatewayUrl/message:send" 2>$null).ToString().Trim()
    $badVersionStatus = (& curl.exe -sS --max-time 10 -o $probeOutput -w '%{http_code}' -X POST `
        -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" -H 'A2A-Version: 0.2.1' `
        -H 'Content-Type: application/a2a+json' --data-binary "@$probeBody" `
        "$gatewayUrl/message:send" 2>$null).ToString().Trim()
    $unauthHeaders = Get-Content -Raw -LiteralPath $unauthHeaderFile -ErrorAction SilentlyContinue
    $hasBasicChallenge = $unauthHeaders -match '(?im)^WWW-Authenticate:\s*Basic'
    if ($unauthStatus -ne '401' -or $badVersionStatus -notin @('400', '406') -or $hasBasicChallenge) {
        throw "security/version probes failed: unauth=$unauthStatus badVersion=$badVersionStatus"
    }
    Write-Output "unauthenticated=$unauthStatus invalid-version=$badVersionStatus"
    $catalogOutput = Join-Path $logRoot 'gateway-catalog.json'
    $catalogArgs = @('-sS', '--max-time', '10', '-o', $catalogOutput, '-w', '%{http_code}',
        '-H', "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY", '-H', 'A2A-Version: 1.0',
        "$gatewayUrl/gateway/v1/agents")
    $catalogStatus = (& curl.exe @catalogArgs 2>$null).ToString().Trim()
    $catalogBody = Get-Content -Raw -LiteralPath $catalogOutput -ErrorAction SilentlyContinue
    $detailStatus = (& curl.exe -sS --max-time 10 -o $probeOutput -w '%{http_code}' `
        -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" -H 'A2A-Version: 1.0' `
        "$gatewayUrl/gateway/v1/agents/echo-b" 2>$null).ToString().Trim()
    $cardStatus = (& curl.exe -sS --max-time 10 -o $probeOutput -w '%{http_code}' `
        -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" -H 'A2A-Version: 1.0' `
        "$gatewayUrl/gateway/v1/agents/echo-b/card" 2>$null).ToString().Trim()
    $catalogBodyValid = -not [string]::IsNullOrWhiteSpace($catalogBody) -and $catalogBody -match '"agents"'
    if ($catalogStatus -ne '200' -or -not $catalogBodyValid -or $detailStatus -ne '200' -or $cardStatus -ne '200') {
        throw "Agent catalog probes failed: list=$catalogStatus detail=$detailStatus card=$cardStatus bodyLength=$($catalogBody.Length)"
    }
    Write-Output "agent-catalog list=$catalogStatus detail=$detailStatus card=$cardStatus"
    $body = '{"message":{"role":"ROLE_USER","parts":[{"text":"hello from smoke test"}]}}'
    $bodyFile = Join-Path $logRoot 'gateway-request.json'
    Set-Content -LiteralPath $bodyFile -Value $body -NoNewline
    $responseFile = Join-Path $logRoot 'gateway-response.json'
    $headerFile = Join-Path $logRoot 'gateway-response.headers'
    $curlArgs = @('-sS', '--max-time', '10', '-D', $headerFile, '-o', $responseFile,
        '-w', '%{http_code}', '-X', 'POST', '-H', "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY", '-H',
        'A2A-Version: 1.0', '-H', 'X-A2A-Target-Agent: echo-b', '-H', 'Content-Type: application/a2a+json',
        '--data-binary', "@$bodyFile", "$gatewayUrl/message:send")
    $status = & curl.exe @curlArgs 2>$null
    $responseBody = Get-Content -Raw -LiteralPath $responseFile -ErrorAction SilentlyContinue
    $gatewayLog = Get-Content -Raw -LiteralPath (Join-Path $logRoot 'gateway.out') -ErrorAction SilentlyContinue
    $forwarded = $gatewayLog -match 'gateway.audit operation=SEND_MESSAGE outcome=SUCCESS'
    if ($status -ne '200' -or [string]::IsNullOrWhiteSpace($responseBody) -or -not $forwarded) {
        Write-Output "response body length=$($responseBody.Length)"
        Write-Output '--- gateway log ---'
        Get-Content (Join-Path $logRoot 'gateway.out') -ErrorAction SilentlyContinue
        Get-Content (Join-Path $logRoot 'gateway.err') -ErrorAction SilentlyContinue
        Write-Output '--- agent logs ---'
        Get-Content (Join-Path $logRoot 'agent-a.out') -ErrorAction SilentlyContinue
        Get-Content (Join-Path $logRoot 'agent-a.err') -ErrorAction SilentlyContinue
        Get-Content (Join-Path $logRoot 'agent-b.out') -ErrorAction SilentlyContinue
        Get-Content (Join-Path $logRoot 'agent-b.err') -ErrorAction SilentlyContinue
        throw "gateway returned unexpected response: HTTP $status body=$responseBody"
    }
    try {
        $sendResponse = $responseBody | ConvertFrom-Json -ErrorAction Stop
        $gatewayTaskId = [string]$sendResponse.task.id
    }
    catch {
        throw "gateway send response is not a task envelope: $responseBody"
    }
    if ([string]::IsNullOrWhiteSpace($gatewayTaskId)) {
        throw "gateway send response did not contain task id: $responseBody"
    }
    $getTaskResponseFile = Join-Path $logRoot 'gateway-get-task-response.json'
    $getTaskStatus = (& curl.exe -sS --max-time 10 -o $getTaskResponseFile -w '%{http_code}' `
        -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" -H 'A2A-Version: 1.0' `
        "$gatewayUrl/gateway/v1/tasks/$gatewayTaskId" 2>$null).ToString().Trim()
    $getTaskBody = Get-Content -Raw -LiteralPath $getTaskResponseFile -ErrorAction SilentlyContinue
    if ($getTaskStatus -ne '200' -or [string]::IsNullOrWhiteSpace($getTaskBody) -or
            -not $getTaskBody.Contains($gatewayTaskId)) {
        throw "gateway task route probe failed: HTTP $getTaskStatus task=$gatewayTaskId body=$getTaskBody"
    }
    Write-Output "task-route get-task=$getTaskStatus"
    $rpcSendBody = '{"jsonrpc":"2.0","id":"smoke-rpc-send","method":"SendMessage","params":{"message":{"messageId":"smoke-rpc-message","role":"ROLE_USER","parts":[{"text":"hello from JSON-RPC smoke test","mediaType":"text/plain"}]}}}'
    $rpcSendBodyFile = Join-Path $logRoot 'gateway-rpc-send-request.json'
    Set-Content -LiteralPath $rpcSendBodyFile -Value $rpcSendBody -NoNewline
    $rpcSendResponseFile = Join-Path $logRoot 'gateway-rpc-send-response.json'
    $rpcSendStatus = (& curl.exe -sS --max-time 10 -o $rpcSendResponseFile -w '%{http_code}' -X POST `
        -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" -H 'A2A-Version: 1.0' `
        -H 'X-A2A-Target-Agent: echo-a' -H 'Content-Type: application/json' -H 'Accept: application/json' `
        --data-binary "@$rpcSendBodyFile" "$gatewayUrl/gateway/v1/a2a" 2>$null).ToString().Trim()
    $rpcSendResponseBody = Get-Content -Raw -LiteralPath $rpcSendResponseFile -ErrorAction SilentlyContinue
    try {
        $rpcSendResponse = $rpcSendResponseBody | ConvertFrom-Json -ErrorAction Stop
        $rpcGatewayTaskId = [string]$rpcSendResponse.result.task.id
    }
    catch {
        throw "gateway JSON-RPC send response is not a task result: $rpcSendResponseBody"
    }
    if ($rpcSendStatus -ne '200' -or [string]::IsNullOrWhiteSpace($rpcGatewayTaskId)) {
        throw "gateway JSON-RPC send failed: HTTP $rpcSendStatus body=$rpcSendResponseBody"
    }
    $rpcGetBody = '{"jsonrpc":"2.0","id":"smoke-rpc-get","method":"GetTask","params":{"id":"' + $rpcGatewayTaskId + '"}}'
    $rpcGetBodyFile = Join-Path $logRoot 'gateway-rpc-get-request.json'
    Set-Content -LiteralPath $rpcGetBodyFile -Value $rpcGetBody -NoNewline
    $rpcGetResponseFile = Join-Path $logRoot 'gateway-rpc-get-response.json'
    $rpcGetStatus = (& curl.exe -sS --max-time 10 -o $rpcGetResponseFile -w '%{http_code}' -X POST `
        -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" -H 'A2A-Version: 1.0' `
        -H 'Content-Type: application/json' -H 'Accept: application/json' --data-binary "@$rpcGetBodyFile" `
        "$gatewayUrl/gateway/v1/a2a" 2>$null).ToString().Trim()
    $rpcGetBodyResponse = Get-Content -Raw -LiteralPath $rpcGetResponseFile -ErrorAction SilentlyContinue
    if ($rpcGetStatus -ne '200' -or -not $rpcGetBodyResponse.Contains($rpcGatewayTaskId)) {
        throw "gateway JSON-RPC task route probe failed: HTTP $rpcGetStatus task=$rpcGatewayTaskId body=$rpcGetBodyResponse"
    }
    Write-Output "json-rpc-task-route get-task=$rpcGetStatus"
    $rpcStreamBody = '{"jsonrpc":"2.0","id":"smoke-rpc-stream","method":"SendStreamingMessage","params":{"message":{"messageId":"smoke-rpc-stream-message","role":"ROLE_USER","parts":[{"text":"hello from JSON-RPC streaming smoke test","mediaType":"text/plain"}]}}}'
    $rpcStreamBodyFile = Join-Path $logRoot 'gateway-rpc-stream-request.json'
    Set-Content -LiteralPath $rpcStreamBodyFile -Value $rpcStreamBody -NoNewline
    $rpcStreamResponseFile = Join-Path $logRoot 'gateway-rpc-stream-response.sse'
    $rpcStreamStatus = (& curl.exe -sS --max-time 15 -o $rpcStreamResponseFile -w '%{http_code}' -X POST `
        -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" -H 'A2A-Version: 1.0' `
        -H 'X-A2A-Target-Agent: echo-a' -H 'Content-Type: application/json' -H 'Accept: text/event-stream' `
        --data-binary "@$rpcStreamBodyFile" "$gatewayUrl/gateway/v1/a2a" 2>$null).ToString().Trim()
    $rpcStreamResponseBody = Get-Content -Raw -LiteralPath $rpcStreamResponseFile -ErrorAction SilentlyContinue
    $taskMatch = [regex]::Match($rpcStreamResponseBody, '"taskId"\s*:\s*"([^"]+)"')
    if ($rpcStreamStatus -ne '200' -or -not $taskMatch.Success) {
        throw "gateway JSON-RPC stream did not return a task id: HTTP $rpcStreamStatus body=$rpcStreamResponseBody"
    }
    $rpcStreamGatewayTaskId = $taskMatch.Groups[1].Value
    $rpcStreamGetBody = '{"jsonrpc":"2.0","id":"smoke-rpc-stream-get","method":"GetTask","params":{"id":"' + $rpcStreamGatewayTaskId + '"}}'
    $rpcStreamGetBodyFile = Join-Path $logRoot 'gateway-rpc-stream-get-request.json'
    Set-Content -LiteralPath $rpcStreamGetBodyFile -Value $rpcStreamGetBody -NoNewline
    $rpcStreamGetResponseFile = Join-Path $logRoot 'gateway-rpc-stream-get-response.json'
    $rpcStreamGetStatus = (& curl.exe -sS --max-time 10 -o $rpcStreamGetResponseFile -w '%{http_code}' -X POST `
        -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" -H 'A2A-Version: 1.0' `
        -H 'Content-Type: application/json' -H 'Accept: application/json' --data-binary "@$rpcStreamGetBodyFile" `
        "$gatewayUrl/gateway/v1/a2a" 2>$null).ToString().Trim()
    $rpcStreamGetBodyResponse = Get-Content -Raw -LiteralPath $rpcStreamGetResponseFile -ErrorAction SilentlyContinue
    if ($rpcStreamGetStatus -ne '200' -or -not $rpcStreamGetBodyResponse.Contains($rpcStreamGatewayTaskId)) {
        throw "gateway JSON-RPC streaming task route probe failed: HTTP $rpcStreamGetStatus task=$rpcStreamGatewayTaskId body=$rpcStreamGetBodyResponse"
    }
    Write-Output "json-rpc-stream-task-route get-task=$rpcStreamGetStatus"
    $skillResponseFile = Join-Path $logRoot 'gateway-skill-response.json'
    $skillStatus = (& curl.exe -sS --max-time 10 -o $skillResponseFile -w '%{http_code}' -X POST `
        -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" -H 'A2A-Version: 1.0' `
        -H 'X-A2A-Target-Skill: code-generation' -H 'Content-Type: application/a2a+json' `
        --data-binary "@$bodyFile" "$gatewayUrl/message:send" 2>$null).ToString().Trim()
    $skillResponseBody = Get-Content -Raw -LiteralPath $skillResponseFile -ErrorAction SilentlyContinue
    if ($skillStatus -ne '200' -or [string]::IsNullOrWhiteSpace($skillResponseBody)) {
        throw "skill-only routing probe failed: HTTP $skillStatus body=$skillResponseBody"
    }
    Write-Output "skill-routing code-generation=$skillStatus"
    if ([string]::IsNullOrWhiteSpace($responseBody)) {
        $responseBody = '<response body verified by gateway forwarding audit>'
    }
    [PSCustomObject]@{ agentA = $cardA.name; agentB = $cardB.name; health = $health.status
        response = $responseBody }
}
finally {
    foreach ($process in $processes) {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    if ($ForceCleanup) { Stop-SamplePorts @($AgentPortA, $AgentPortB, $GatewayPort) }
    Remove-Item -LiteralPath $logRoot -Recurse -Force -ErrorAction SilentlyContinue
}
