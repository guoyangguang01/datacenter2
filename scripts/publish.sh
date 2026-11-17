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
PACKAGES="$ROOT/artifacts/packages"
DEPENDENCIES="$ROOT/artifacts/dependencies"
RUNTIME="$ROOT/artifacts/runtime"
INFLUX_TEMPLATES="$ROOT/deploy/influxdb"
INFLUX_VERSION="2.7.12"

mkdir -p "$OUTPUT" "$PACKAGES" "$DEPENDENCIES" "$RUNTIME"

verify_sha256() {
    local file="$1"
    local expected="$2"
    local actual
    actual="$(sha256sum "$file" | awk '{print $1}')"
    if [[ "$actual" != "$expected" ]]; then
        echo "SHA256 mismatch for $file. Expected $expected, found $actual." >&2
        exit 1
    fi
}

download_checked() {
    local file="$1"
    local url="$2"
    local expected="$3"
    if [[ ! -f "$file" ]]; then
        curl -fL --retry 3 --output "$file" "$url"
    fi
    verify_sha256 "$file" "$expected"
}

prepare_influx() {
    local rid="$1"
    local archive="$2"
    local url="$3"
    local expected="$4"
    local stage="$RUNTIME/influxdb-$rid"

    download_checked "$DEPENDENCIES/$archive" "$url" "$expected"
    rm -rf -- "$stage"
    mkdir -p "$stage"

    if [[ "$rid" == "win-x64" ]]; then
        unzip -q "$DEPENDENCIES/$archive" -d "$stage"
        mv "$stage/README.md" "$stage/INFLUXDB-README.md"
        cp "$INFLUX_TEMPLATES/start-influxdb.ps1" "$stage/"
        cp "$INFLUX_TEMPLATES/stop-influxdb.ps1" "$stage/"
    else
        mkdir -p "$stage/_extracted"
        tar -xzf "$DEPENDENCIES/$archive" -C "$stage/_extracted"
        local package_root
        package_root="$(find "$stage/_extracted" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
        cp "$package_root/usr/bin/influxd" "$stage/influxd"
        cp "$package_root/usr/share/influxdb/LICENSE" "$stage/LICENSE"
        cp "$package_root/usr/share/influxdb/README.md" "$stage/INFLUXDB-README.md"
        cp "$INFLUX_TEMPLATES/start-influxdb.sh" "$stage/"
        cp "$INFLUX_TEMPLATES/stop-influxdb.sh" "$stage/"
        rm -rf -- "$stage/_extracted"
    fi

    cp "$INFLUX_TEMPLATES/README.md" "$stage/README.md"
    printf 'InfluxDB OSS %s\n' "$INFLUX_VERSION" >"$stage/VERSION.txt"
}

copy_influx_to_server() {
    local rid="$1"
    local publish_dir="$2"
    rm -rf -- "$publish_dir/influxdb"
    cp -R "$RUNTIME/influxdb-$rid" "$publish_dir/influxdb"
}

prepare_influx \
    "win-x64" \
    "influxdb2-2.7.12-windows.zip" \
    "https://dl.influxdata.com/influxdb/releases/v2.7.12/influxdb2-2.7.12-windows.zip" \
    "adb952c67eaeea742d81899367ec95defa26b47a0fa99dc773ab4de822e9072d"
prepare_influx \
    "linux-x64" \
    "influxdb2-2.7.12_linux_amd64.tar.gz" \
    "https://dl.influxdata.com/influxdb/releases/v2.7.12/influxdb2-2.7.12_linux_amd64.tar.gz" \
    "825641e678b4a0f6e2094293f326b472269f30c3d02a989bee8c37bfa706f8d7"

publish_target() {
    local name="$1"
    local project="$2"
    local rid="$3"
    local archive="$4"
    local publish_dir="$OUTPUT/$name"

    "$DOTNET" publish "$project" -c Release -r "$rid" --self-contained true -o "$publish_dir"

    if [[ "$name" == server-* ]]; then
        copy_influx_to_server "$rid" "$publish_dir"
    fi

    case "$archive" in
        *.zip)
            rm -f -- "$PACKAGES/$archive"
            (cd "$publish_dir" && zip -qr "$PACKAGES/$archive" .)
            ;;
        *.tar.gz)
            tar -czf "$PACKAGES/$archive" -C "$publish_dir" .
            ;;
        *)
            echo "Unsupported archive format: $archive" >&2
            exit 1
            ;;
    esac
}

publish_target "server-win-x64" "$ROOT/src/SdnCustom.Server.App/SdnCustom.Server.App.csproj" "win-x64" "server-win-x64.zip"
publish_target "client-win-x64" "$ROOT/src/SdnCustom.Client.App/SdnCustom.Client.App.csproj" "win-x64" "client-win-x64.zip"
publish_target "server-linux-x64" "$ROOT/src/SdnCustom.Server.App/SdnCustom.Server.App.csproj" "linux-x64" "server-linux-x64.tar.gz"
publish_target "client-linux-x64" "$ROOT/src/SdnCustom.Client.App/SdnCustom.Client.App.csproj" "linux-x64" "client-linux-x64.tar.gz"

rm -f -- "$PACKAGES/influxdb-win-x64.zip" "$PACKAGES/influxdb-linux-x64.tar.gz"
(
    cd "$RUNTIME/influxdb-win-x64"
    zip -qr "$PACKAGES/influxdb-win-x64.zip" .
)
tar -czf "$PACKAGES/influxdb-linux-x64.tar.gz" -C "$RUNTIME/influxdb-linux-x64" .
