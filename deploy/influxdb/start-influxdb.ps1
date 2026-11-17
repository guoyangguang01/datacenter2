[CmdletBinding()]
param(
    [string]$Address = "127.0.0.1",
    [int]$Port = 8087,
    [string]$Username = "admin",
    [string]$Password = "sdncustom-admin",
    [string]$Org = "sdncustom",
    [string]$Bucket = "sdncustom",
    [string]$Token = "sdncustom-dev-token"
)

$ErrorActionPreference = "Stop"
$influxRoot = $PSScriptRoot
$influxd = Join-Path $influxRoot "influxd.exe"
$dataDir = Join-Path $influxRoot "data"
$logDir = Join-Path $influxRoot "logs"
$pidFile = Join-Path $dataDir "influxd.pid"
$stdoutLog = Join-Path $logDir "influxd.out.log"
$stderrLog = Join-Path $logDir "influxd.err.log"
$baseUrl = "http://${Address}:$Port"

if (-not (Test-Path -LiteralPath $influxd)) {
    throw "InfluxDB executable not found: $influxd"
}

New-Item -ItemType Directory -Force -Path $dataDir, $logDir | Out-Null

function Test-InfluxReady {
    try {
        $health = Invoke-RestMethod -Uri "$baseUrl/health" -TimeoutSec 2
        return $health.status -eq "pass" -and $health.name -eq "influxdb"
    }
    catch {
        return $false
    }
}

if (Test-InfluxReady) {
    Write-Host "InfluxDB is already running at $baseUrl"
    exit 0
}

if (Test-Path -LiteralPath $pidFile) {
    $existingPid = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue
    $existing = if ($existingPid) { Get-Process -Id $existingPid -ErrorAction SilentlyContinue } else { $null }
    if ($existing -and $existing.ProcessName -eq "influxd") {
        throw "InfluxDB process $existingPid exists but is not ready at $baseUrl. Check $stderrLog"
    }

    Remove-Item -LiteralPath $pidFile -Force
}

$arguments = @(
    "--http-bind-address=${Address}:$Port",
    "--bolt-path=`"$(Join-Path $dataDir 'influxd.bolt')`"",
    "--engine-path=`"$(Join-Path $dataDir 'engine')`"",
    "--reporting-disabled"
)

$process = Start-Process `
    -FilePath $influxd `
    -ArgumentList $arguments `
    -WindowStyle Hidden `
    -PassThru `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog

Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ASCII

try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if ($process.HasExited) {
            break
        }

        if (Test-InfluxReady) {
            $ready = $true
            break
        }

        Start-Sleep -Milliseconds 500
    }

    if (-not $ready) {
        $details = if (Test-Path -LiteralPath $stderrLog) {
            Get-Content -LiteralPath $stderrLog -Tail 30 | Out-String
        }
        else {
            "No stderr log was produced."
        }
        throw "InfluxDB did not become ready at $baseUrl.`n$details"
    }

    $setupStatus = Invoke-RestMethod -Uri "$baseUrl/api/v2/setup" -TimeoutSec 10
    if ($setupStatus.allowed) {
        $setup = @{
            username = $Username
            password = $Password
            org = $Org
            bucket = $Bucket
            token = $Token
            retentionPeriodSeconds = 0
        } | ConvertTo-Json

        Invoke-RestMethod `
            -Method Post `
            -Uri "$baseUrl/api/v2/setup" `
            -ContentType "application/json" `
            -Body $setup `
            -TimeoutSec 30 | Out-Null

        Write-Host "InfluxDB initialized: org=$Org bucket=$Bucket"
    }

    Write-Host "InfluxDB started at $baseUrl (PID $($process.Id))"
    Write-Host "Data directory: $dataDir"
}
catch {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        $process.WaitForExit()
    }

    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    throw
}
