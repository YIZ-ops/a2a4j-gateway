[CmdletBinding()]
param(
    [switch]$SkipInstall,
    [string]$ReportPath = "",
    [string]$MarkdownPath = ""
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $root 'target/release-gates/osv-scan.json'
}
if ([string]::IsNullOrWhiteSpace($MarkdownPath)) {
    $MarkdownPath = Join-Path $root 'docs/agent-gateway/mvp-dependency-scan-2026-08-01.md'
}
New-Item -ItemType Directory -Path (Split-Path -Parent $ReportPath) -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $MarkdownPath) -Force | Out-Null

function Invoke-Maven {
    param([string[]]$Arguments)
    Push-Location $root
    try {
        & (Join-Path $root 'mvnw.cmd') @Arguments 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Maven command failed with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

if (-not $SkipInstall) {
    # Install the reactor first so module-level dependency trees resolve local a2a4j artifacts.
    Invoke-Maven @('-q', '-DskipTests', 'install')
}

$modules = @(
    'a2a4j-gateway-spring-boot-starter',
    'a2a4j-samples/gateway-hello-world',
    'a2a4j-samples/server-hello-world',
    'a2a4j-samples/client-hello-world',
    'a2a4j-spring-boot-starter/a2a4j-server-spring-boot-starter',
    'a2a4j-spring-boot-starter/a2a4j-client-spring-boot-starter'
)
$treeDirectory = Join-Path (Split-Path -Parent $ReportPath) 'dependency-trees'
New-Item -ItemType Directory -Path $treeDirectory -Force | Out-Null
$dependencies = @{}
$treeFiles = New-Object System.Collections.Generic.List[string]

foreach ($module in $modules) {
    $pom = Join-Path $root (Join-Path $module 'pom.xml')
    $safeName = ($module -replace '[^A-Za-z0-9_.-]', '_')
    $treeFile = Join-Path $treeDirectory ($safeName + '.txt')
    Invoke-Maven @('-q', '-f', $pom, 'dependency:tree', '-Dscope=runtime', '-DoutputType=text', "-DoutputFile=$treeFile")
    $treeFiles.Add($treeFile)
    foreach ($line in Get-Content -LiteralPath $treeFile) {
        $match = [regex]::Match($line, '(?<group>[A-Za-z0-9_.-]+):(?<artifact>[A-Za-z0-9_.-]+):(?<type>[A-Za-z0-9_.-]+):(?<version>[^:\s]+):(?<scope>compile|runtime)')
        if (-not $match.Success) { continue }
        $group = $match.Groups['group'].Value
        $artifact = $match.Groups['artifact'].Value
        $version = $match.Groups['version'].Value
        $key = "$group`:$artifact`:$version"
        if (-not $dependencies.ContainsKey($key)) {
            $dependencies[$key] = [PSCustomObject]@{
                group = $group
                artifact = $artifact
                version = $version
                modules = New-Object System.Collections.Generic.List[string]
            }
        }
        if (-not $dependencies[$key].modules.Contains($module)) {
            $dependencies[$key].modules.Add($module)
        }
    }
}

$dependencyList = @($dependencies.Values | Sort-Object group, artifact, version)
$vulnerabilities = New-Object System.Collections.Generic.List[object]
$queryResults = @()
$batchSize = 100
for ($offset = 0; $offset -lt $dependencyList.Count; $offset += $batchSize) {
    $end = [Math]::Min($offset + $batchSize - 1, $dependencyList.Count - 1)
    $batch = @($dependencyList[$offset..$end] | ForEach-Object {
        [PSCustomObject]@{
            package = [PSCustomObject]@{ ecosystem = 'Maven'; name = "$($_.group):$($_.artifact)" }
            version = $_.version
        }
    })
    $body = @{ queries = $batch } | ConvertTo-Json -Depth 8
    try {
        $response = Invoke-RestMethod -Method Post -Uri 'https://api.osv.dev/v1/querybatch' -ContentType 'application/json' -Body $body
    }
    catch {
        throw "OSV querybatch failed: $($_.Exception.Message)"
    }
    $queryResults += @($response.results)
}

$detailCache = @{}
for ($i = 0; $i -lt $dependencyList.Count; $i++) {
    $result = if ($i -lt $queryResults.Count) { $queryResults[$i] } else { $null }
    if ($null -eq $result -or $null -eq $result.PSObject.Properties['vulns']) {
        continue
    }
    foreach ($vulnRef in @($result.vulns)) {
        $id = [string]$vulnRef.id
        if ([string]::IsNullOrWhiteSpace($id)) {
            continue
        }
        if (-not $detailCache.ContainsKey($id)) {
            try {
                $detailCache[$id] = Invoke-RestMethod -Uri ("https://api.osv.dev/v1/vulns/{0}" -f $id)
            }
            catch {
                throw "OSV vulnerability detail lookup failed for ${id}: $($_.Exception.Message)"
            }
        }
        $detail = $detailCache[$id]
        $severity = [string]$detail.database_specific.severity
        if ([string]::IsNullOrWhiteSpace($severity)) { $severity = 'UNKNOWN' }
        $vulnerabilities.Add([PSCustomObject]@{
            id = $id
            aliases = @($detail.aliases)
            summary = [string]$detail.summary
            severity = $severity.ToUpperInvariant()
            package = "$($dependencyList[$i].group):$($dependencyList[$i].artifact)"
            version = $dependencyList[$i].version
            modules = @($dependencyList[$i].modules)
            references = @($detail.references | ForEach-Object { $_.url })
        })
    }
}

$high = @($vulnerabilities | Where-Object { $_.severity -in @('HIGH', 'CRITICAL') })
$result = [PSCustomObject]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    source = 'https://api.osv.dev'
    dependencyCount = $dependencyList.Count
    dependencies = $dependencyList
    vulnerabilities = @($vulnerabilities | Sort-Object severity, package, id)
    highOrCritical = $high.Count
    passed = ($high.Count -eq 0)
    treeFiles = @($treeFiles)
}
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('# MVP dependency vulnerability scan (OSV)')
$lines.Add('')
$lines.Add(('Generated: `{0}`; dependencies: `{1}`; source: [{2}]({2}).' -f $result.generatedAt, $result.dependencyCount, $result.source))
$lines.Add('')
$lines.Add('Runtime compile/runtime dependencies are checked through the OSV Maven API. HIGH/CRITICAL findings block formal release.')
$lines.Add('')
$lines.Add('| Package | Version | Severity | ID | Summary |')
$lines.Add('|---|---:|---|---|---|')
if ($vulnerabilities.Count -eq 0) {
    $lines.Add('| - | - | - | - | No vulnerabilities found |')
}
else {
    foreach ($v in ($vulnerabilities | Sort-Object severity, package, id)) {
        $summary = ($v.summary -replace '\|', '\|' -replace '\r?\n', ' ')
        $lines.Add(('| `{0}` | `{1}` | **{2}** | `{3}` | {4} |' -f $v.package, $v.version, $v.severity, $v.id, $summary))
    }
}
$lines.Add('')
$conclusion = if ($result.passed) { 'PASS' } else { 'FAIL' }
$lines.Add(('Conclusion: **{0}** (HIGH/CRITICAL={1}). Machine-readable report: `{2}`.' -f $conclusion, $result.highOrCritical, $ReportPath))
$lines -join [Environment]::NewLine | Set-Content -LiteralPath $MarkdownPath -Encoding UTF8

if (-not $result.passed) {
    Write-Error ("OSV release gate failed: {0} HIGH/CRITICAL vulnerabilities found; see {1}" -f $high.Count, $MarkdownPath)
    exit 1
}
Write-Output ("OSV release gate passed; evidence=$ReportPath")
