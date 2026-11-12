param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. ./scripts/env.ps1

function Invoke-BackroomSql([string]$Sql) {
    $backroomSqlOutput = & docker compose exec -T db psql -U $env:POSTGRES_USER -d $env:POSTGRES_DB -v ON_ERROR_STOP=1 -Atc $Sql
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL smoke-test query failed.' }
    return ($backroomSqlOutput -join "`n").Trim()
}

function Wait-Backroom {
    $backroomDeadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $backroomDeadline) {
        try {
            $backroomHealth = Invoke-RestMethod -Uri ($backroomBaseUrl + '/actuator/health') -TimeoutSec 5
            if ($backroomHealth.status -eq 'UP') { return }
        } catch {
            # Startup and database reconnection can briefly return an unavailable response.
        }
        Start-Sleep -Seconds 2
    }
    & docker compose logs --tail 80 app
    throw 'The application did not become healthy within 120 seconds.'
}

$backroomPort = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { '8080' }
$backroomBaseUrl = 'http://127.0.0.1:' + $backroomPort
$backroomComposeArguments = @('compose', '--profile', 'app', 'up', '-d')
if (-not $SkipBuild) { $backroomComposeArguments += '--build' }
& docker @backroomComposeArguments
if ($LASTEXITCODE -ne 0) { throw 'Container startup or build failed.' }

Wait-Backroom
$backroomPage = Invoke-WebRequest -Uri $backroomBaseUrl -UseBasicParsing
if ($backroomPage.StatusCode -ne 200 -or $backroomPage.Content -notmatch 'Backroom' -or
    $backroomPage.Content -notmatch 'id="root"') { throw 'Packaged homepage verification failed.' }
$backroomAssets = [regex]::Matches($backroomPage.Content, '(?:src|href)="(/assets/[^"]+)"')
if ($backroomAssets.Count -eq 0) { throw 'No compiled frontend assets were found.' }
foreach ($backroomAsset in $backroomAssets) {
    $backroomAssetResponse = Invoke-WebRequest -Uri ($backroomBaseUrl + $backroomAsset.Groups[1].Value) -UseBasicParsing
    if ($backroomAssetResponse.StatusCode -ne 200) { throw 'A frontend asset could not be loaded.' }
}
$backroomUserId = (& docker compose exec -T app id -u | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $backroomUserId -eq '0') { throw 'The runtime must run as a non-root user.' }

# Create one uniquely named diagnostic table, then remove only that table.
$backroomProbeSuffix = [guid]::NewGuid().ToString('N')
$backroomProbeTable = 'phase0_smoke_' + $backroomProbeSuffix
if ($backroomProbeTable -notmatch '^phase0_smoke_[a-f0-9]{32}$') { throw 'Unexpected probe identifier.' }
$backroomProbeCreated = $false
try {
    Invoke-BackroomSql ("CREATE TABLE public." + $backroomProbeTable + " (token text NOT NULL); INSERT INTO public." + $backroomProbeTable + " VALUES ('" + $backroomProbeSuffix + "');") | Out-Null
    $backroomProbeCreated = $true
    & docker compose up -d --wait --force-recreate db
    if ($LASTEXITCODE -ne 0) { throw 'Database recreation with the existing volume failed.' }
    & docker compose restart app
    if ($LASTEXITCODE -ne 0) { throw 'Application restart failed.' }
    Wait-Backroom
    $backroomPersisted = Invoke-BackroomSql ('SELECT token FROM public.' + $backroomProbeTable)
    if ($backroomPersisted -ne $backroomProbeSuffix) { throw 'The persisted probe was not preserved.' }
} finally {
    if ($backroomProbeCreated) {
        Invoke-BackroomSql ('DROP TABLE public.' + $backroomProbeTable) | Out-Null
    }
}
Write-Host 'PASS: health, packaged homepage, compiled assets, non-root runtime, and data persistence across container recreation.'
Write-Host ('Backroom is running at ' + $backroomBaseUrl)
