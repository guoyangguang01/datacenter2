#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFLUXD="$ROOT/influxd"
DATA_DIR="$ROOT/data"
LOG_DIR="$ROOT/logs"
PID_FILE="$DATA_DIR/influxd.pid"
ADDRESS="${SDNCUSTOM_INFLUX_ADDRESS:-127.0.0.1}"
PORT="${SDNCUSTOM_INFLUX_PORT:-8087}"
USERNAME="${SDNCUSTOM_INFLUX_USERNAME:-admin}"
PASSWORD="${SDNCUSTOM_INFLUX_PASSWORD:-sdncustom-admin}"
ORG="${SDNCUSTOM_INFLUX_ORG:-sdncustom}"
BUCKET="${SDNCUSTOM_INFLUX_BUCKET:-sdncustom}"
TOKEN="${SDNCUSTOM_INFLUX_TOKEN:-sdncustom-dev-token}"
BASE_URL="http://$ADDRESS:$PORT"

if [[ ! -x "$INFLUXD" ]]; then
    chmod +x "$INFLUXD"
fi

mkdir -p "$DATA_DIR" "$LOG_DIR"

is_ready() {
    curl -fsS --max-time 2 "$BASE_URL/health" 2>/dev/null \
        | grep -Eq '"status"[[:space:]]*:[[:space:]]*"pass"'
}

if is_ready; then
    echo "InfluxDB is already running at $BASE_URL"
    exit 0
fi

if [[ -f "$PID_FILE" ]]; then
    existing_pid="$(cat "$PID_FILE")"
    if [[ -n "$existing_pid" ]] && kill -0 "$existing_pid" 2>/dev/null; then
        echo "InfluxDB process $existing_pid exists but is not ready at $BASE_URL. Check $LOG_DIR/influxd.log" >&2
        exit 1
    fi
    rm -f "$PID_FILE"
fi

nohup "$INFLUXD" \
    --http-bind-address="$ADDRESS:$PORT" \
    --bolt-path="$DATA_DIR/influxd.bolt" \
    --engine-path="$DATA_DIR/engine" \
    --reporting-disabled \
    >"$LOG_DIR/influxd.log" 2>&1 &

pid=$!
printf '%s\n' "$pid" >"$PID_FILE"

ready=false
for _ in $(seq 1 120); do
    if ! kill -0 "$pid" 2>/dev/null; then
        break
    fi
    if is_ready; then
        ready=true
        break
    fi
    sleep 0.5
done

if [[ "$ready" != true ]]; then
    kill "$pid" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "InfluxDB did not become ready at $BASE_URL. Check $LOG_DIR/influxd.log" >&2
    exit 1
fi

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

setup_status="$(curl -fsS --max-time 10 "$BASE_URL/api/v2/setup")"
if grep -Eq '"allowed"[[:space:]]*:[[:space:]]*true' <<<"$setup_status"; then
    setup_body="$(printf '{"username":"%s","password":"%s","org":"%s","bucket":"%s","token":"%s","retentionPeriodSeconds":0}' \
        "$(json_escape "$USERNAME")" \
        "$(json_escape "$PASSWORD")" \
        "$(json_escape "$ORG")" \
        "$(json_escape "$BUCKET")" \
        "$(json_escape "$TOKEN")")"
    curl -fsS --max-time 30 \
        -H "Content-Type: application/json" \
        -X POST \
        --data "$setup_body" \
        "$BASE_URL/api/v2/setup" >/dev/null
    echo "InfluxDB initialized: org=$ORG bucket=$BUCKET"
fi

echo "InfluxDB started at $BASE_URL (PID $pid)"
echo "Data directory: $DATA_DIR"
