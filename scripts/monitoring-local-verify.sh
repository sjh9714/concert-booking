#!/usr/bin/env bash
set -euo pipefail

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

wait_for_url() {
  local url="$1"
  local label="$2"
  local attempts="${3:-60}"

  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for ${label}: ${url}" >&2
  exit 1
}

require_command curl
require_command docker
require_command python3

BASE_URL="${BASE_URL:-http://localhost:8080}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
MONITORING_ADMIN_EMAIL="${MONITORING_ADMIN_EMAIL:-monitor-admin@local}"
MONITORING_ADMIN_PASSWORD="${MONITORING_ADMIN_PASSWORD:-monitor-admin-local-password}"
TOKEN_FILE="${TOKEN_FILE:-monitoring/.generated/concert-booking.token}"

cat >&2 <<EOF
This script verifies a local monitoring harness only.
Run the app separately with:
  SPRING_PROFILES_ACTIVE=local-monitoring ./gradlew bootRun
EOF

wait_for_url "${BASE_URL}/actuator/health" "Spring Boot app health"

login_payload="$(
  MONITORING_ADMIN_EMAIL="$MONITORING_ADMIN_EMAIL" \
  MONITORING_ADMIN_PASSWORD="$MONITORING_ADMIN_PASSWORD" \
  python3 - <<'PY'
import json
import os

print(json.dumps({
    "email": os.environ["MONITORING_ADMIN_EMAIL"],
    "password": os.environ["MONITORING_ADMIN_PASSWORD"],
}))
PY
)"

token="$(
  curl -fsS "${BASE_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "$login_payload" \
    | python3 -c 'import json, sys; print(json.load(sys.stdin)["token"])'
)"

mkdir -p "$(dirname "$TOKEN_FILE")"
printf "%s" "$token" >"$TOKEN_FILE"

curl -fsS "${BASE_URL}/actuator/prometheus" \
  -H "Authorization: Bearer ${token}" \
  | grep -q "concert_booking_"

docker compose \
  -f docker-compose.yml \
  -f docker-compose.monitoring.yml \
  up -d prometheus grafana

wait_for_url "${PROMETHEUS_URL}/-/ready" "Prometheus readiness"

PROMETHEUS_URL="$PROMETHEUS_URL" bash scripts/capture-monitoring-evidence.sh

echo "Local monitoring harness verified. This is not production observability evidence."
