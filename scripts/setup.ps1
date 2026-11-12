$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
if ((node --version) -notmatch '^v24\.') { throw 'Install Node.js 24 LTS before setup.' }
if ((npm --version) -notmatch '^11\.') { throw 'Use npm 11 for the committed lockfile.' }
$backroomJava = Join-Path (Get-Location).Path '.tools/jdk-21'
if (-not (Test-Path -LiteralPath (Join-Path $backroomJava 'bin/java.exe'))) {
    New-Item -ItemType Directory -Path '.tools' -Force | Out-Null
    Write-Host 'Downloading a portable Temurin Java 21 JDK for this project...'
    $backroomAssets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $backroomPackage = ($backroomAssets | Select-Object -First 1).binary.package
    Invoke-WebRequest $backroomPackage.link -OutFile '.tools/jdk-21.zip'
    if ((Get-FileHash '.tools/jdk-21.zip' -Algorithm SHA256).Hash -ne $backroomPackage.checksum) {
        throw 'Java download checksum mismatch.'
    }
    $backroomUnpack = Join-Path (Get-Location).Path ('.tools/jdk-unpack-' + [guid]::NewGuid().ToString('N'))
    Expand-Archive '.tools/jdk-21.zip' -DestinationPath $backroomUnpack
    $backroomExtracted = Get-ChildItem -LiteralPath $backroomUnpack -Directory | Select-Object -First 1
    Move-Item -LiteralPath $backroomExtracted.FullName -Destination $backroomJava
}
if (-not (Test-Path -LiteralPath '.env')) { Copy-Item '.env.example' '.env' }
. ./scripts/env.ps1
java -version
docker info --format '{{.OSType}}'
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop (Linux containers), then run setup again.' }
docker compose up -d --wait db
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL startup failed. Check Docker and POSTGRES_PORT.' }
npm --prefix frontend ci
if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed. On Windows, stop the Vite dev server before retrying; npm ci replaces native binaries.' }
Write-Host 'Setup complete. See README.md to start the backend and frontend in separate terminals.'
