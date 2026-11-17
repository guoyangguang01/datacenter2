#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$ROOT/data/influxd.pid"

if [[ ! -f "$PID_FILE" ]]; then
    echo "InfluxDB PID file was not found; nothing to stop."
    exit 0
fi

pid="$(cat "$PID_FILE")"
if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$PID_FILE"
    echo "InfluxDB is not running; removed the stale PID file."
    exit 0
fi

kill "$pid"
for _ in $(seq 1 40); do
    if ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "InfluxDB stopped."
        exit 0
    fi
    sleep 0.5
done

echo "InfluxDB did not stop within 20 seconds (PID $pid)." >&2
exit 1
