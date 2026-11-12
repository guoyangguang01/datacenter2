#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOTNET="$ROOT/.dotnet/dotnet"
[[ -x "$DOTNET" ]] || DOTNET="dotnet"
export DOTNET_CLI_HOME="$ROOT/.dotnet-home"
export NUGET_PACKAGES="$ROOT/.nuget"
export AVALONIA_TELEMETRY_OPTOUT=1
cd "$ROOT"
exec "$DOTNET" run --project src/SdnCustom.Server.App/SdnCustom.Server.App.csproj
