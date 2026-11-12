$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. ./scripts/env.ps1
docker info --format '{{.OSType}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker must be running; integration tests are required.' }
npm --prefix frontend ci
if ($LASTEXITCODE -ne 0) { throw 'npm ci failed. On Windows, stop the Vite dev server before retrying; npm ci replaces native binaries.' }
npm --prefix frontend run build
if ($LASTEXITCODE -ne 0) { throw 'Frontend checks failed.' }
& ./mvnw.cmd -B -ntp clean verify
if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
