#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOTNET="$ROOT/.dotnet/dotnet"
[[ -x "$DOTNET" ]] || DOTNET="dotnet"
export DOTNET_CLI_HOME="$ROOT/.dotnet-home"
export NUGET_PACKAGES="$ROOT/.nuget"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export AVALONIA_TELEMETRY_OPTOUT=1
OUTPUT="$ROOT/artifacts/publish"

"$DOTNET" publish "$ROOT/src/SdnCustom.Server.App/SdnCustom.Server.App.csproj" -c Release -r win-x64 --self-contained false -o "$OUTPUT/server-win-x64"
"$DOTNET" publish "$ROOT/src/SdnCustom.Client.App/SdnCustom.Client.App.csproj" -c Release -r win-x64 --self-contained false -o "$OUTPUT/client-win-x64"
"$DOTNET" publish "$ROOT/src/SdnCustom.Server.App/SdnCustom.Server.App.csproj" -c Release -r linux-x64 --self-contained false -o "$OUTPUT/server-linux-x64"
"$DOTNET" publish "$ROOT/src/SdnCustom.Client.App/SdnCustom.Client.App.csproj" -c Release -r linux-x64 --self-contained false -o "$OUTPUT/client-linux-x64"
