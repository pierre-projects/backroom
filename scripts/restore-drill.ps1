# Proves a backup can be restored: backup -> restore into a scratch database -> compare the row count of every
# table with the live database -> check the stock ledger invariants -> drop the scratch database.
# Fails (non-zero exit) on any mismatch. Drill dumps go to backups/drill so they never rotate out real backups.
param([ValidateRange(1, 5)][int]$Attempts = 3)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. ./scripts/env.ps1 -NoJava
$backroomUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'backroom' }
$backroomLive = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'backroom' }
$backroomDrillDirectory = 'backups/drill'

function Invoke-DrillSql([string]$Database, [string]$Sql) {
    $output = & docker compose exec -T db psql -U $backroomUser -d $Database -v ON_ERROR_STOP=1 -AtX -c $Sql
    if ($LASTEXITCODE -ne 0) { throw ('Query against ' + $Database + ' failed.') }
    return @($output | Where-Object { $_ -ne '' })
}

# Exact row counts for every table in the public schema, as "table|count" lines (Flyway history included).
$backroomCountSql = @"
SELECT table_name || '|' || (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text
FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name
"@

# Each balance must equal the sum of its movements; each request's received quantity must equal its non-reversed receipts.
$backroomLedgerSql = @"
SELECT 'stock slots checked|' || (SELECT count(*) FROM stock_balances)
UNION ALL
SELECT 'stock slots out of balance|' || count(*) FROM stock_balances b
  FULL JOIN (SELECT organization_id, item_id, location_id, sum(quantity_change) AS total
             FROM stock_movements GROUP BY organization_id, item_id, location_id) m
  USING (organization_id, item_id, location_id)
  WHERE b.quantity IS NULL OR b.quantity <> coalesce(m.total, 0)
UNION ALL
SELECT 'restock requests checked|' || (SELECT count(*) FROM restock_requests)
UNION ALL
SELECT 'restock requests out of balance|' || count(*) FROM restock_requests r
  LEFT JOIN (SELECT rc.organization_id, rc.request_id, sum(rc.quantity) AS total FROM restock_receipts rc
             WHERE NOT EXISTS (SELECT 1 FROM stock_operations o
                               WHERE o.organization_id = rc.organization_id AND o.reverses_operation_id = rc.stock_operation_id)
             GROUP BY rc.organization_id, rc.request_id) t
    ON t.organization_id = r.organization_id AND t.request_id = r.id
  WHERE r.received_quantity <> coalesce(t.total, 0)
"@

function Test-Ledger([string]$Database) {
    $failures = 0
    foreach ($line in (Invoke-DrillSql $Database $backroomLedgerSql)) {
        $name, $value = $line -split '\|', 2
        Write-Host ('  ' + $Database + ': ' + $name + ' = ' + $value)
        if ($name -like '*out of balance' -and [long]$value -ne 0) { $failures++ }
    }
    return $failures
}

$backroomScratch = 'backroom_drill_' + [guid]::NewGuid().ToString('N').Substring(0, 12)
if ($backroomScratch -notmatch '^backroom_drill_[a-f0-9]{12}$') { throw 'Unexpected scratch database name.' }
$backroomFailures = 0
try {
    # The live database may change while the dump runs. Counts taken before and after the dump must agree,
    # otherwise the comparison would be meaningless; retry a few times before giving up.
    $backroomExpected = $null
    for ($backroomAttempt = 1; $backroomAttempt -le $Attempts -and -not $backroomExpected; $backroomAttempt++) {
        $backroomBefore = Invoke-DrillSql $backroomLive $backroomCountSql
        & ./scripts/backup.ps1 -Database $backroomLive -OutputDirectory $backroomDrillDirectory -Keep 3 | Out-Host
        $backroomAfter = Invoke-DrillSql $backroomLive $backroomCountSql
        if (($backroomBefore -join "`n") -eq ($backroomAfter -join "`n")) { $backroomExpected = $backroomBefore }
        else { Write-Host ('Live row counts changed during backup attempt ' + $backroomAttempt + '; retrying.') }
    }
    if (-not $backroomExpected) { throw 'The live database kept changing during every backup attempt; run the drill at a quieter time.' }
    $backroomDump = Get-ChildItem -LiteralPath $backroomDrillDirectory -Filter 'backroom-*.dump' -File |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1

    & ./scripts/restore.ps1 -File $backroomDump.FullName -Database $backroomScratch | Out-Host
    $backroomRestored = Invoke-DrillSql $backroomScratch $backroomCountSql

    $backroomLiveCounts = @{}; foreach ($line in $backroomExpected) { $t, $c = $line -split '\|', 2; $backroomLiveCounts[$t] = $c }
    $backroomScratchCounts = @{}; foreach ($line in $backroomRestored) { $t, $c = $line -split '\|', 2; $backroomScratchCounts[$t] = $c }
    $backroomTables = @(@($backroomLiveCounts.Keys) + @($backroomScratchCounts.Keys) | Sort-Object -Unique)
    $backroomRows = 0
    Write-Host ('Row counts (' + $backroomLive + ' -> ' + $backroomScratch + '):')
    foreach ($backroomTable in $backroomTables) {
        $expected = $backroomLiveCounts[$backroomTable]; $actual = $backroomScratchCounts[$backroomTable]
        $mark = if ($expected -eq $actual) { 'ok' } else { $backroomFailures++; 'MISMATCH' }
        if ($expected) { $backroomRows += [long]$expected }
        Write-Host ('  {0,-28} {1,8} {2,8}  {3}' -f $backroomTable, $expected, $actual, $mark)
    }
    if ($backroomTables.Count -eq 0) { $backroomFailures++; Write-Host 'No tables were found; the database is not migrated.' }

    Write-Host 'Ledger invariants:'
    $backroomFailures += Test-Ledger $backroomScratch
    $backroomFailures += Test-Ledger $backroomLive
} finally {
    & docker compose exec -T db dropdb -U $backroomUser --if-exists --force $backroomScratch | Out-Host
    if ($LASTEXITCODE -ne 0) { Write-Host ('WARNING: could not drop scratch database ' + $backroomScratch + '.') }
}
if ($backroomFailures -ne 0) { throw ('FAIL: restore drill found ' + $backroomFailures + ' mismatch(es).') }
Write-Host ('PASS: restore drill. ' + $backroomTables.Count + ' tables and ' + $backroomRows + ' rows matched after restoring ' +
    $backroomDump.Name + ' (' + [math]::Round($backroomDump.Length / 1KB, 1) + ' KB); ledger invariants hold; scratch database dropped.')
