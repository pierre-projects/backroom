$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. ./scripts/env.ps1
& ./mvnw.cmd spring-boot:run
if ($LASTEXITCODE -ne 0) { throw 'Backend stopped with an error.' }
