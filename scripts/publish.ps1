$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot\..").Path
$dotnet = if (Test-Path "$root\.dotnet\dotnet.exe") { "$root\.dotnet\dotnet.exe" } else { "dotnet" }
$env:DOTNET_CLI_HOME = "$root\.dotnet-home"
$env:NUGET_PACKAGES = "$root\.nuget"
$env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"
$env:AVALONIA_TELEMETRY_OPTOUT = "1"
$output = "$root\artifacts\publish"
$packages = "$root\artifacts\packages"
$dependencies = "$root\artifacts\dependencies"
$runtime = "$root\artifacts\runtime"
$influxVersion = "2.7.12"
$influxTemplates = "$root\deploy\influxdb"

New-Item -ItemType Directory -Force -Path $output, $packages, $dependencies, $runtime | Out-Null

$influxDistributions = @{
    "win-x64" = @{
        Archive = "influxdb2-2.7.12-windows.zip"
        Url = "https://dl.influxdata.com/influxdb/releases/v2.7.12/influxdb2-2.7.12-windows.zip"
        Sha256 = "adb952c67eaeea742d81899367ec95defa26b47a0fa99dc773ab4de822e9072d"
    }
    "linux-x64" = @{
        Archive = "influxdb2-2.7.12_linux_amd64.tar.gz"
        Url = "https://dl.influxdata.com/influxdb/releases/v2.7.12/influxdb2-2.7.12_linux_amd64.tar.gz"
        Sha256 = "825641e678b4a0f6e2094293f326b472269f30c3d02a989bee8c37bfa706f8d7"
    }
}

function Assert-ChildPath {
    param(
        [string]$Path,
        [string]$Parent
    )

    $resolvedParent = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    if (-not $resolvedPath.StartsWith(
        $resolvedParent + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify path outside $resolvedParent`: $resolvedPath"
    }
}

function Get-InfluxDistribution {
    param([string]$Rid)

    $definition = $influxDistributions[$Rid]
    if (-not $definition) {
        throw "No InfluxDB distribution is configured for $Rid."
    }

    $archive = Join-Path $dependencies $definition.Archive
    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Host "Downloading InfluxDB $influxVersion for $Rid..."
        Invoke-WebRequest -UseBasicParsing -Uri $definition.Url -OutFile $archive
    }

    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $definition.Sha256) {
        throw "SHA256 mismatch for $archive. Expected $($definition.Sha256), found $hash."
    }

    $stage = Join-Path $runtime "influxdb-$Rid"
    Assert-ChildPath -Path $stage -Parent $runtime
    if (Test-Path -LiteralPath $stage) {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
    New-Item -ItemType Directory -Path $stage | Out-Null

    if ($Rid -eq "win-x64") {
        Expand-Archive -LiteralPath $archive -DestinationPath $stage
        Move-Item -LiteralPath (Join-Path $stage "README.md") -Destination (Join-Path $stage "INFLUXDB-README.md")
        Copy-Item -Path (Join-Path $influxTemplates "start-influxdb.ps1") -Destination $stage
        Copy-Item -Path (Join-Path $influxTemplates "stop-influxdb.ps1") -Destination $stage
    }
    else {
        $extracted = Join-Path $stage "_extracted"
        New-Item -ItemType Directory -Path $extracted | Out-Null
        tar -xzf $archive -C $extracted
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to extract $archive."
        }

        $packageRoot = Get-ChildItem -LiteralPath $extracted -Directory | Select-Object -First 1
        Copy-Item -LiteralPath (Join-Path $packageRoot.FullName "usr/bin/influxd") -Destination (Join-Path $stage "influxd")
        Copy-Item -LiteralPath (Join-Path $packageRoot.FullName "usr/share/influxdb/LICENSE") -Destination $stage
        Copy-Item -LiteralPath (Join-Path $packageRoot.FullName "usr/share/influxdb/README.md") -Destination (Join-Path $stage "INFLUXDB-README.md")
        Copy-Item -Path (Join-Path $influxTemplates "start-influxdb.sh") -Destination $stage
        Copy-Item -Path (Join-Path $influxTemplates "stop-influxdb.sh") -Destination $stage
        Remove-Item -LiteralPath $extracted -Recurse -Force
    }

    Copy-Item -Path (Join-Path $influxTemplates "README.md") -Destination $stage
    Set-Content -LiteralPath (Join-Path $stage "VERSION.txt") -Value "InfluxDB OSS $influxVersion" -Encoding ASCII
    return $stage
}

function Copy-InfluxToServer {
    param(
        [string]$Source,
        [string]$PublishDir
    )

    $destination = Join-Path $PublishDir "influxdb"
    Assert-ChildPath -Path $destination -Parent $PublishDir
    if (Test-Path -LiteralPath $destination) {
        Remove-Item -LiteralPath $destination -Recurse -Force
    }
    Copy-Item -LiteralPath $Source -Destination $destination -Recurse
}

$influxStages = @{
    "win-x64" = Get-InfluxDistribution -Rid "win-x64"
    "linux-x64" = Get-InfluxDistribution -Rid "linux-x64"
}

$targets = @(
    @{ Name = "server-win-x64"; Project = "$root\src\SdnCustom.Server.App\SdnCustom.Server.App.csproj"; Rid = "win-x64"; Archive = "server-win-x64.zip" },
    @{ Name = "client-win-x64"; Project = "$root\src\SdnCustom.Client.App\SdnCustom.Client.App.csproj"; Rid = "win-x64"; Archive = "client-win-x64.zip" },
    @{ Name = "server-linux-x64"; Project = "$root\src\SdnCustom.Server.App\SdnCustom.Server.App.csproj"; Rid = "linux-x64"; Archive = "server-linux-x64.tar.gz" },
    @{ Name = "client-linux-x64"; Project = "$root\src\SdnCustom.Client.App\SdnCustom.Client.App.csproj"; Rid = "linux-x64"; Archive = "client-linux-x64.tar.gz" }
)

foreach ($target in $targets) {
    $publishDir = Join-Path $output $target.Name
    & $dotnet publish $target.Project -c Release -r $target.Rid --self-contained true -o $publishDir
    if ($LASTEXITCODE -ne 0) {
        throw "Publish failed for $($target.Name) with exit code $LASTEXITCODE."
    }

    if ($target.Name.StartsWith("server-")) {
        Copy-InfluxToServer -Source $influxStages[$target.Rid] -PublishDir $publishDir
    }

    $archive = Join-Path $packages $target.Archive
    if (Test-Path -LiteralPath $archive) {
        Remove-Item -LiteralPath $archive -Force
    }

    if ($target.Archive.EndsWith(".zip")) {
        Compress-Archive -Path (Join-Path $publishDir "*") -DestinationPath $archive -CompressionLevel Optimal
    }
    else {
        tar -czf $archive -C $publishDir .
        if ($LASTEXITCODE -ne 0) {
            throw "Archive failed for $($target.Name) with exit code $LASTEXITCODE."
        }
    }
}

$influxPackages = @(
    @{ Stage = $influxStages["win-x64"]; Archive = "influxdb-win-x64.zip" },
    @{ Stage = $influxStages["linux-x64"]; Archive = "influxdb-linux-x64.tar.gz" }
)

foreach ($influxPackage in $influxPackages) {
    $archive = Join-Path $packages $influxPackage.Archive
    if (Test-Path -LiteralPath $archive) {
        Remove-Item -LiteralPath $archive -Force
    }

    if ($influxPackage.Archive.EndsWith(".zip")) {
        Compress-Archive -Path (Join-Path $influxPackage.Stage "*") -DestinationPath $archive -CompressionLevel Optimal
    }
    else {
        tar -czf $archive -C $influxPackage.Stage .
        if ($LASTEXITCODE -ne 0) {
            throw "Archive failed for $($influxPackage.Archive) with exit code $LASTEXITCODE."
        }
    }
}
