# Runs the browser end-to-end suite (scripts/e2e.ps1 -SkipBuild) and samples the app container's memory while it runs.
# Prints the peak against the mem_limit in compose.yaml. -Build rebuilds the image first; -Image picks the tag
# (compose.yaml reads BACKROOM_IMAGE), so a measurement never replaces the backroom:local image other runs use.
param(
    [switch]$Build,
    [ValidatePattern('^[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9._-]+$')][string]$Image = 'backroom:local',
    [ValidateRange(250, 10000)][int]$IntervalMilliseconds = 1000
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. ./scripts/env.ps1

function ConvertTo-Bytes([string]$Text) {
    if ($Text -notmatch '^\s*([\d.]+)\s*([KMGT]?i?B)\s*$') { return $null }
    $scale = @{ 'B' = 1; 'KB' = 1e3; 'MB' = 1e6; 'GB' = 1e9; 'TB' = 1e12; 'KiB' = 1KB; 'MiB' = 1MB; 'GiB' = 1GB; 'TiB' = 1TB }
    return [long]([double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture) * $scale[$Matches[2]])
}

# Docker CLI calls that may fail while containers come and go; errors are expected and ignored.
function Invoke-Quiet([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & docker @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return (($output | Out-String).Trim())
}

$backroomLimitMatch = [regex]::Match((Get-Content -Raw -LiteralPath 'compose.yaml'), '(?m)^\s*mem_limit:\s*(\d+)([kmg]?)\s*$')
if (-not $backroomLimitMatch.Success) { throw 'compose.yaml has no mem_limit for the app service.' }
$backroomLimit = [long]$backroomLimitMatch.Groups[1].Value * @{ '' = 1; 'k' = 1KB; 'm' = 1MB; 'g' = 1GB }[$backroomLimitMatch.Groups[2].Value]

$backroomSavedImage = $env:BACKROOM_IMAGE
$env:BACKROOM_IMAGE = $Image
if ($Build) {
    & docker compose --profile app build app
    if ($LASTEXITCODE -ne 0) { $env:BACKROOM_IMAGE = $backroomSavedImage; throw ('Building ' + $Image + ' failed.') }
}
# Labels are parsed from {{.Labels}}: Windows PowerShell can't pass embedded double quotes to docker --format.
function Get-AppContainers([switch]$All) {
    $arguments = @('ps', '--filter', 'label=com.docker.compose.service=app', '--format', '{{.ID}} {{.Labels}}')
    if ($All) { $arguments += '-a' }
    foreach ($row in (@(Invoke-Quiet $arguments) -split "`r?`n")) {
        if ($row -match '^(\S+) .*com\.docker\.compose\.project=([^,]+)') { [pscustomobject]@{ Id = $Matches[1]; Project = $Matches[2] } }
    }
}
$backroomKnownProjects = @(Get-AppContainers -All | ForEach-Object { $_.Project })

$backroomLogDirectory = Join-Path ([IO.Path]::GetTempPath()) ('backroom-memory-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $backroomLogDirectory | Out-Null
$backroomShell = (Get-Process -Id $PID).Path
$backroomRun = Start-Process -FilePath $backroomShell -NoNewWindow -PassThru `
    -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'scripts/e2e.ps1', '-SkipBuild') `
    -RedirectStandardOutput (Join-Path $backroomLogDirectory 'e2e.out.log') -RedirectStandardError (Join-Path $backroomLogDirectory 'e2e.err.log')
$null = $backroomRun.Handle # keeps ExitCode readable after exit on Windows PowerShell

$backroomContainer = $null
$backroomSamples = New-Object System.Collections.Generic.List[long]
$backroomCgroupPeak = 0L; $backroomFirst = $null; $backroomStarted = [DateTime]::UtcNow
Write-Host 'Running the e2e suite and sampling app memory...'
while (-not $backroomRun.HasExited) {
    if (-not $backroomContainer) {
        foreach ($candidate in (Get-AppContainers)) {
            if ($candidate.Project -match '^backroom-e2e-[a-f0-9]{12}$' -and $backroomKnownProjects -notcontains $candidate.Project) {
                $backroomContainer = $candidate.Id
                Write-Host ('Sampling container ' + $candidate.Id + ' of ' + $candidate.Project)
            }
        }
    }
    if ($backroomContainer) {
        $usage = Invoke-Quiet @('stats', '--no-stream', '--format', '{{.MemUsage}}', $backroomContainer)
        if ($usage) {
            $bytes = ConvertTo-Bytes (($usage -split '/')[0])
            if ($bytes) { $backroomSamples.Add($bytes); if ($null -eq $backroomFirst) { $backroomFirst = $bytes } }
        }
        # cgroup v2 high-water mark (includes page cache); catches spikes between samples.
        $peak = Invoke-Quiet @('exec', $backroomContainer, 'cat', '/sys/fs/cgroup/memory.peak')
        if ($peak -match '^\d+$' -and [long]$peak -gt $backroomCgroupPeak) { $backroomCgroupPeak = [long]$peak }
    }
    Start-Sleep -Milliseconds $IntervalMilliseconds
}
$backroomRun.WaitForExit()
$backroomExit = $backroomRun.ExitCode
$env:BACKROOM_IMAGE = $backroomSavedImage
$backroomSeconds = [math]::Round(([DateTime]::UtcNow - $backroomStarted).TotalSeconds)
Get-Content -LiteralPath (Join-Path $backroomLogDirectory 'e2e.out.log') -Tail 25 | Out-Host
if ($backroomExit -ne 0) {
    Get-Content -LiteralPath (Join-Path $backroomLogDirectory 'e2e.err.log') -Tail 40 | Out-Host
    Write-Host ('Full e2e output kept in ' + $backroomLogDirectory)
} else {
    Remove-Item -LiteralPath $backroomLogDirectory -Recurse -Force
}

if ($backroomSamples.Count -eq 0) { throw 'No memory samples were collected; the e2e application never started.' }
$backroomPeak = ($backroomSamples | Measure-Object -Maximum).Maximum
$backroomAverage = ($backroomSamples | Measure-Object -Average).Average
function Format-MiB([double]$Bytes) { return ('{0:N0} MiB' -f ($Bytes / 1MB)) }
Write-Host ''
Write-Host ('Memory of the app container (' + $Image + ') during e2e (' + $backroomSamples.Count + ' samples over ' + $backroomSeconds + ' s, limit ' + (Format-MiB $backroomLimit) + ' from compose.yaml):')
Write-Host ('  first sample (after startup):  ' + (Format-MiB $backroomFirst))
Write-Host ('  average:                       ' + (Format-MiB $backroomAverage))
Write-Host ('  peak (docker stats):           ' + (Format-MiB $backroomPeak) + ('  ({0:P0} of limit)' -f ($backroomPeak / $backroomLimit)))
if ($backroomCgroupPeak -gt 0) {
    Write-Host ('  peak (cgroup memory.peak):     ' + (Format-MiB $backroomCgroupPeak) + ('  ({0:P0} of limit, includes page cache)' -f ($backroomCgroupPeak / $backroomLimit)))
}
if ($backroomExit -ne 0) { throw ('The e2e suite failed (exit ' + $backroomExit + '); memory numbers are from a failed run.') }
if ($backroomPeak -ge $backroomLimit) { throw 'FAIL: the app reached its memory limit.' }
Write-Host 'PASS: e2e suite passed and the app stayed under its memory limit.'
# The last docker probe usually hits the container after teardown; don't leak its exit code.
exit 0
