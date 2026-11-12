# Restores a pg_dump custom-format file into a named database in the Compose db container.
# The target database is dropped and recreated. Restoring over the live database (POSTGRES_DB) is refused unless
# -Force is given; stop the app first (docker compose --profile app stop app) so nothing writes during the restore.
param(
    [Parameter(Mandatory = $true)][string]$File,
    [Parameter(Mandatory = $true)][string]$Database,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. ./scripts/env.ps1 -NoJava
$backroomUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'backroom' }
$backroomLive = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'backroom' }
if ($Database -notmatch '^[a-z_][a-z0-9_]{0,62}$') { throw 'Database names may contain lowercase letters, digits and underscores.' }
if ($Database -in @('postgres', 'template0', 'template1')) { throw 'Refusing to restore over a PostgreSQL system database.' }
if ($Database -eq $backroomLive -and -not $Force) {
    throw ('Refusing to replace the live database ' + $backroomLive + '. Restore into another name to inspect it, or stop the app and re-run with -Force.')
}
$backroomSource = Get-Item -LiteralPath $File
if ($backroomSource.Length -le 0) { throw 'The dump file is empty.' }
if ($Database -eq $backroomLive) {
    Write-Host ('Replacing the LIVE database ' + $backroomLive + ' from ' + $backroomSource.FullName + '. Connected sessions are terminated.')
}

$backroomInside = '/tmp/backroom-restore-' + [guid]::NewGuid().ToString('N') + '.dump'
try {
    & docker compose cp $backroomSource.FullName ('db:' + $backroomInside) | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Copying the dump into the db container failed. Is the Compose db service running?' }
    & docker compose exec -T db pg_restore --list $backroomInside | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The file is not a readable pg_dump custom-format archive.' }
    & docker compose exec -T db dropdb -U $backroomUser --if-exists --force $Database | Out-Host
    if ($LASTEXITCODE -ne 0) { throw ('Dropping database ' + $Database + ' failed.') }
    & docker compose exec -T db createdb -U $backroomUser $Database | Out-Host
    if ($LASTEXITCODE -ne 0) { throw ('Creating database ' + $Database + ' failed.') }
    & docker compose exec -T db pg_restore -U $backroomUser -d $Database --no-owner --no-privileges --exit-on-error $backroomInside | Out-Host
    if ($LASTEXITCODE -ne 0) { throw ('pg_restore into ' + $Database + ' failed.') }
} finally {
    & docker compose exec -T db rm -f $backroomInside | Out-Null
}
Write-Host ('PASS: restored ' + $backroomSource.Name + ' into database ' + $Database + '.')
