[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipSmoke,
    [switch]$SkipPerformance,
    [switch]$SkipDependencyScan,
    [string]$ReportPath = ""
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $root 'target/release-gates/mvp-release-gates.json'
}
$reportDirectory = Split-Path -Parent $ReportPath
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null

$checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param([string]$Name, [bool]$Passed, [string]$Evidence, [string]$Output = '')
    $checks.Add([PSCustomObject]@{ name = $Name; passed = $Passed; evidence = $Evidence; output = $Output })
    if (-not $Passed) {
        # Keep collecting evidence so the final JSON report records every failed gate.
        Write-Warning "release gate failed: $Name - $Evidence"
    }
}

function Invoke-Checked {
    param([string]$Name, [string]$File, [string[]]$Arguments, [string]$Evidence)
    Push-Location $root
    try {
        $savedErrorAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        if ($File -like '*.ps1') {
            # Invoke through a fresh PowerShell process so named script parameters remain
            # named when the argument list is splatted on Windows PowerShell 5.1.
            $output = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $File @Arguments 2>&1 | Out-String)
        }
        else {
            $output = (& $File @Arguments 2>&1 | Out-String)
        }
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $savedErrorAction
    }
    finally {
        Pop-Location
    }
    Add-Check $Name ($exitCode -eq 0) $Evidence $output.Trim()
}

if (-not $SkipBuild) {
    Invoke-Checked 'full-tests' (Join-Path $root 'mvnw.cmd') @('-q', 'clean', 'test', '-DskipTests=false', '-Dmaven.clean.failOnError=false') `
        'mvnw.cmd clean test -DskipTests=false -Dmaven.clean.failOnError=false (locked local sample jars are non-fatal; exec classifier is used for smoke)'
    Invoke-Checked 'package' (Join-Path $root 'mvnw.cmd') @('-q', 'package', '-DskipTests') `
        'mvnw.cmd package -DskipTests'
}

Push-Location $root
try {
    $savedErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $diffCheck = (& git diff --check 2>&1 | Out-String)
    $diffExit = $LASTEXITCODE
    $ErrorActionPreference = $savedErrorAction
}
finally {
    Pop-Location
}
Add-Check 'diff-check' ($diffExit -eq 0) 'git diff --check' $diffCheck.Trim()

# This scan deliberately examines committed/runtime-like material only. Example keys and
# env:// references are allowed; bearer/API-key values, private keys and non-placeholder
# client secrets are not. The same scanner is used in CI and in the release evidence.
$scanRoots = @('a2a4j-core', 'a2a4j-gateway-api', 'a2a4j-gateway-core',
    'a2a4j-gateway-spring-boot-starter', 'a2a4j-samples', 'a2a4j-spring-boot-starter', 'tools') |
    ForEach-Object { Join-Path $root $_ }
$secretPatterns = @(
    '(?im)\bAuthorization\s*[:=]\s*["'']?Bearer\s+[A-Za-z0-9._~+/=-]{20,}',
    '(?im)\bX-A2A-API-Key\s*[:=]\s*["'']?(?!\$\{|env://|your-|example|test-|dummy)[A-Za-z0-9._~+/=-]{20,}',
    '(?im)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    '(?im)\b(?:client_secret|client-secret|aws_secret_access_key|private_key)\s*[:=]\s*["'']?(?!\$\{|env://|your-|example|test-|dummy)[A-Za-z0-9._~+/=-]{16,}'
)
$findings = New-Object System.Collections.Generic.List[string]
foreach ($scanRoot in $scanRoots) {
    if (-not (Test-Path $scanRoot)) { continue }
    Get-ChildItem -LiteralPath $scanRoot -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\target\\|\\.git\\' -and $_.Extension -in @('.java', '.yml', '.yaml', '.properties', '.json', '.ps1', '.md', '.log') } |
        ForEach-Object {
            $text = Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue
            foreach ($pattern in $secretPatterns) {
                if ($text -match $pattern) {
                    $findings.Add(('{0}: secret-like value matched' -f $_.FullName))
                    break
                }
            }
        }
}
Add-Check 'secret-scan' ($findings.Count -eq 0) 'runtime/source secret scan (no bearer/API-key/private-key/client-secret values)' ($findings -join [Environment]::NewLine)

# A small, deterministic non-blocking policy scan complements the E2E tests. Blocking calls
# are permitted only in lifecycle shutdown or the scheduled discovery refresh, never in the
# request/streaming pipeline. This gives Windows contributors a check even without a native
# BlockHound agent; CI may additionally run BlockHound/DAST scanners.
$blockingFindings = New-Object System.Collections.Generic.List[string]
$gatewayMain = @('a2a4j-gateway-api/src/main', 'a2a4j-gateway-core/src/main',
    'a2a4j-gateway-spring-boot-starter/src/main') | ForEach-Object { Join-Path $root $_ }
foreach ($gatewayRoot in $gatewayMain) {
    Get-ChildItem -LiteralPath $gatewayRoot -Recurse -Filter '*.java' -File |
        ForEach-Object {
            $relative = $_.FullName.Substring($root.Length + 1)
            $content = Get-Content -LiteralPath $_.FullName -Raw
            if ($content -match '\.block(?:First|Last)?\s*\(' -and
                $relative -notmatch 'AgentCardRefreshScheduler\.java$|ReactorNettyAgentTransport\.java$') {
                $blockingFindings.Add($relative)
            }
            if ($content -match 'Thread\.sleep\s*\(') {
                $blockingFindings.Add($relative + ': Thread.sleep')
            }
        }
}
Add-Check 'nonblocking-policy' ($blockingFindings.Count -eq 0) 'gateway request pipeline contains no blocking calls' ($blockingFindings -join [Environment]::NewLine)

if (-not $SkipDependencyScan) {
    Invoke-Checked 'dependency-osv-scan' (Join-Path $root 'tools/g10-osv-scan.ps1') @(
        '-ReportPath', (Join-Path $reportDirectory 'osv-scan.json'),
        '-MarkdownPath', (Join-Path $root 'docs/agent-gateway/mvp-dependency-scan-2026-08-01.md')
    ) 'tools/g10-osv-scan.ps1; OSV Maven querybatch with HIGH/CRITICAL blocking'
}

if (-not $SkipSmoke) {
    Invoke-Checked 'sample-smoke' (Join-Path $root 'tools/g9-smoke.ps1') @(
        '-AgentPortA', '18191', '-AgentPortB', '18192', '-GatewayPort', '18199'
    ) 'tools/g9-smoke.ps1 (isolated ports 18191/18192/18199)'
}

if (-not $SkipPerformance) {
    Invoke-Checked 'performance-baseline' (Join-Path $root 'tools/g10-performance.ps1') @() `
        'tools/g10-performance.ps1; report is docs/agent-gateway/mvp-performance-2026-08-01.md'
}

$result = [PSCustomObject]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    repository = $root
    checks = $checks
    passed = (@($checks | Where-Object { -not $_.passed }).Count -eq 0)
}
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
if (-not $result.passed) { exit 1 }
Write-Output ("release gates passed; evidence=" + $ReportPath)
