#!/usr/bin/env bash
set -euo pipefail

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_command curl
require_command python3

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
OUT_DIR="${OUT_DIR:-docs/evidence/monitoring/prometheus-${timestamp}}"

mkdir -p "$OUT_DIR"

curl_json() {
  local path="$1"
  local output="$2"

  curl -fsS "${PROMETHEUS_URL}${path}" >"${OUT_DIR}/${output}"
}

cat >"${OUT_DIR}/metadata.txt" <<EOF
Concert Booking local Prometheus evidence capture
Captured at: ${timestamp}
PROMETHEUS_URL=${PROMETHEUS_URL}

This capture is local monitoring evidence only. It is not a production
alerting, dashboard operation, tracing, or SLO claim.
EOF

curl_json "/api/v1/targets?state=active" "targets.json"
curl_json "/api/v1/rules" "rules.json"
curl_json "/api/v1/query?query=up%7Bjob%3D%22concert-booking-local%22%7D" "up-query.json"
curl_json "/api/v1/query?query=concert_booking_outbox_events" "outbox-events-query.json"
curl_json "/api/v1/query?query=concert_booking_queue_token_validation_failures_total" "queue-token-failures-query.json"

python3 scripts/validate-monitoring-evidence.py "$OUT_DIR"

cat >"${OUT_DIR}/README.md" <<EOF
# Local Prometheus Evidence Capture

Produced by \`scripts/capture-monitoring-evidence.sh\`.

Captured files:

- \`metadata.txt\`
- \`targets.json\`
- \`rules.json\`
- \`up-query.json\`
- \`outbox-events-query.json\`
- \`queue-token-failures-query.json\`
- \`capture-summary.json\`

This artifact proves only that a local Prometheus server exposed the expected
target, rules, and key query series at capture time. It does not prove production
alert firing, Grafana dashboard operation, tracing, SLO, or on-call readiness.
EOF

echo "Monitoring evidence captured under: ${OUT_DIR}"
