$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot\..").Path
$dotnet = if (Test-Path "$root\.dotnet\dotnet.exe") { "$root\.dotnet\dotnet.exe" } else { "dotnet" }
$env:DOTNET_CLI_HOME = "$root\.dotnet-home"
$env:NUGET_PACKAGES = "$root\.nuget"
$env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"
$env:AVALONIA_TELEMETRY_OPTOUT = "1"
$output = "$root\artifacts\publish"

& $dotnet publish "$root\src\SdnCustom.Server.App\SdnCustom.Server.App.csproj" -c Release -r win-x64 --self-contained false -o "$output\server-win-x64"
& $dotnet publish "$root\src\SdnCustom.Client.App\SdnCustom.Client.App.csproj" -c Release -r win-x64 --self-contained false -o "$output\client-win-x64"
& $dotnet publish "$root\src\SdnCustom.Server.App\SdnCustom.Server.App.csproj" -c Release -r linux-x64 --self-contained false -o "$output\server-linux-x64"
& $dotnet publish "$root\src\SdnCustom.Client.App\SdnCustom.Client.App.csproj" -c Release -r linux-x64 --self-contained false -o "$output\client-linux-x64"
