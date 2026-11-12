# Dot-source this file: . ./scripts/env.ps1
$backroomProjectRoot = Split-Path -Parent $PSScriptRoot
$backroomPortableJava = Join-Path $backroomProjectRoot '.tools/jdk-21'
if (Test-Path -LiteralPath (Join-Path $backroomPortableJava 'bin/java.exe')) {
    $env:JAVA_HOME = $backroomPortableJava
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin'))) {
    throw 'Java 21 is required. Run ./scripts/setup.ps1 or set JAVA_HOME to a Java 21 JDK.'
}
$backroomJavaBin = Join-Path $env:JAVA_HOME 'bin'
if (($env:PATH -split [IO.Path]::PathSeparator) -notcontains $backroomJavaBin) {
    $env:PATH = $backroomJavaBin + [IO.Path]::PathSeparator + $env:PATH
}
$backroomEnvFile = Join-Path $backroomProjectRoot '.env'
if (Test-Path -LiteralPath $backroomEnvFile) {
    foreach ($backroomEnvLine in Get-Content -LiteralPath $backroomEnvFile) {
        if ($backroomEnvLine -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $backroomEnvName = $Matches[1]
            $backroomEnvValue = $Matches[2].Trim()
            if ($backroomEnvValue.Length -ge 2 -and
                (($backroomEnvValue.StartsWith('"') -and $backroomEnvValue.EndsWith('"')) -or
                 ($backroomEnvValue.StartsWith("'") -and $backroomEnvValue.EndsWith("'")))) {
                $backroomEnvValue = $backroomEnvValue.Substring(1, $backroomEnvValue.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($backroomEnvName, $backroomEnvValue, 'Process')
        }
    }
}
if ($env:SERVER_PORT) { $env:BACKROOM_API_URL = 'http://127.0.0.1:' + $env:SERVER_PORT }
