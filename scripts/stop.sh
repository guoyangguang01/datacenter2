#!/usr/bin/env bash
set -euo pipefail
pkill -f 'SdnCustom.Server.App' 2>/dev/null || true
pkill -f 'SdnCustom.Client.App' 2>/dev/null || true
