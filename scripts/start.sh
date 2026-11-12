#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/scripts/start-server.sh" &
sleep 2
exec "$ROOT/scripts/start-client.sh"
