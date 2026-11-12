#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOTNET="$ROOT/.dotnet/dotnet"
[[ -x "$DOTNET" ]] || DOTNET="dotnet"
export DOTNET_CLI_HOME="$ROOT/.dotnet-home"
export NUGET_PACKAGES="$ROOT/.nuget"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export AVALONIA_TELEMETRY_OPTOUT=1
cd "$ROOT"
"$DOTNET" restore SDNCustom.slnx
"$DOTNET" build SDNCustom.slnx --no-restore
