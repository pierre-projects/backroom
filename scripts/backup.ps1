# Backs up the Compose PostgreSQL database to backups/backroom-YYYYMMDD-HHmm.dump (pg_dump custom format)
# and keeps the newest -Keep dumps in that folder. The dump is written inside the db container and copied out,
# so no binary data passes through a PowerShell pipeline.
param(
    [string]$Database,
    [string]$OutputDirectory = 'backups',
    [ValidateRange(1, 1000)][int]$Keep = 14
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. ./scripts/env.ps1 -NoJava
if (-not $Database) { $Database = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'backroom' } }
$backroomUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'backroom' }
if ($Database -notmatch '^[a-z_][a-z0-9_]{0,62}$') { throw 'Database names may contain lowercase letters, digits and underscores.' }

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$backroomStamp = (Get-Date).ToString('yyyyMMdd-HHmm')
$backroomTarget = Join-Path $OutputDirectory ('backroom-' + $backroomStamp + '.dump')
if (Test-Path -LiteralPath $backroomTarget) {
    # A second backup in the same minute gets seconds rather than overwriting the first.
    $backroomTarget = Join-Path $OutputDirectory ('backroom-' + (Get-Date).ToString('yyyyMMdd-HHmmss') + '.dump')
}
$backroomInside = '/tmp/backroom-backup-' + [guid]::NewGuid().ToString('N') + '.dump'
try {
    & docker compose exec -T db pg_dump -U $backroomUser -d $Database -Fc --no-owner -f $backroomInside | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed. Is the Compose db service running (docker compose up -d --wait db)?' }
    # A dump that pg_restore can't list is not a backup.
    & docker compose exec -T db pg_restore --list $backroomInside | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The new dump could not be read back with pg_restore --list.' }
    & docker compose cp ('db:' + $backroomInside) $backroomTarget | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Copying the dump out of the db container failed.' }
} finally {
    & docker compose exec -T db rm -f $backroomInside | Out-Null
}
$backroomFile = Get-Item -LiteralPath $backroomTarget
if ($backroomFile.Length -le 0) { throw 'The backup file is empty.' }

$backroomOld = Get-ChildItem -LiteralPath $OutputDirectory -Filter 'backroom-*.dump' -File |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -Skip $Keep
foreach ($backroomStale in $backroomOld) { Remove-Item -LiteralPath $backroomStale.FullName }

Write-Host ('PASS: backed up database ' + $Database + ' to ' + $backroomFile.FullName + ' (' +
    [math]::Round($backroomFile.Length / 1KB, 1) + ' KB). Kept the newest ' + $Keep + '; removed ' + @($backroomOld).Count + '.')
