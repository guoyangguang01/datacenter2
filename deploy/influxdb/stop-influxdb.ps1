$ErrorActionPreference = "Stop"
$pidFile = Join-Path (Join-Path $PSScriptRoot "data") "influxd.pid"

if (-not (Test-Path -LiteralPath $pidFile)) {
    Write-Host "InfluxDB PID file was not found; nothing to stop."
    exit 0
}

$processId = Get-Content -LiteralPath $pidFile -ErrorAction Stop
$process = Get-Process -Id $processId -ErrorAction SilentlyContinue
if (-not $process) {
    Remove-Item -LiteralPath $pidFile -Force
    Write-Host "InfluxDB is not running; removed the stale PID file."
    exit 0
}

if ($process.ProcessName -ne "influxd") {
    throw "PID $processId belongs to '$($process.ProcessName)', not InfluxDB. The PID file was left unchanged."
}

Stop-Process -Id $process.Id -Force
$process.WaitForExit()
Remove-Item -LiteralPath $pidFile -Force
Write-Host "InfluxDB stopped."
