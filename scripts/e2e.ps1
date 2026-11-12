param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. ./scripts/env.ps1
$backroomSavedEnvironment = @{}
foreach ($backroomVariable in @('POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','POSTGRES_PORT','SERVER_PORT','BACKROOM_E2E_URL','PLAYWRIGHT_CHANNEL')) {
    $backroomSavedEnvironment[$backroomVariable] = [Environment]::GetEnvironmentVariable($backroomVariable, 'Process')
}
$backroomTestProject = 'backroom-e2e-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
if ($backroomTestProject -notmatch '^backroom-e2e-[a-f0-9]{12}$') { throw 'Unexpected test project name.' }
try {
    $env:POSTGRES_DB = 'backroom_e2e'
    $env:POSTGRES_USER = 'backroom'
    $env:POSTGRES_PASSWORD = [guid]::NewGuid().ToString('N')
    $env:POSTGRES_PORT = '0'
    $env:SERVER_PORT = '0'
    $backroomStart = @('compose', '-p', $backroomTestProject, '--profile', 'app', 'up', '-d')
    if (-not $SkipBuild) { $backroomStart += '--build' } else { $backroomStart += '--no-build' }
    & docker @backroomStart
    if ($LASTEXITCODE -ne 0) { throw 'The isolated browser-test application failed to start.' }
    $backroomAddress = (& docker compose -p $backroomTestProject port app 8080 | Out-String).Trim()
    $env:BACKROOM_E2E_URL = 'http://' + $backroomAddress
    $backroomDeadline = [DateTime]::UtcNow.AddSeconds(120)
    Write-Host ('Waiting for the isolated test application at ' + $env:BACKROOM_E2E_URL)
    $backroomReady = $false
    while ([DateTime]::UtcNow -lt $backroomDeadline) {
        try {
            if ((Invoke-RestMethod ($env:BACKROOM_E2E_URL + '/actuator/health') -TimeoutSec 5).status -eq 'UP') {
                $backroomReady = $true; break
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $backroomReady) { throw 'The browser-test application did not become healthy.' }
    if ($env:OS -eq 'Windows_NT' -and (Test-Path 'C:/Program Files/Google/Chrome/Application/chrome.exe')) {
        $env:PLAYWRIGHT_CHANNEL = 'chrome'
    } else {
        npm --prefix frontend exec -- playwright install --with-deps chromium
        if ($LASTEXITCODE -ne 0) { throw 'Playwright browser installation failed.' }
    }
    npm --prefix frontend run test:e2e
    if ($LASTEXITCODE -ne 0) { throw 'Browser verification failed.' }
} catch {
    & docker compose -p $backroomTestProject --profile app logs --tail 60
    throw
} finally {
    & docker compose -p $backroomTestProject --profile app down --volumes
    foreach ($backroomVariable in $backroomSavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($backroomVariable, $backroomSavedEnvironment[$backroomVariable], 'Process')
    }
}
