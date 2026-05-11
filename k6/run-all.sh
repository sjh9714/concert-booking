#!/bin/bash
#
# k6 부하 테스트 자동 실행 스크립트
#
# 기본 실행:
#   bash k6/run-all.sh
#
# smoke 실행:
#   SMOKE=1 STRATEGY=distributed SCENARIO=scenario-f VUS=1 bash k6/run-all.sh
#
# 결과 경로:
#   k6/results/{timestamp}/{strategy}/{scenario}/run-{n}/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS_ROOT="${RESULTS_ROOT:-$PROJECT_DIR/k6/results/$TIMESTAMP}"
SCHEDULE_ID="${SCHEDULE_ID:-1}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_COUNT="${USER_COUNT:-200}"

if [ "${SMOKE:-0}" = "1" ]; then
    STRATEGIES=("${STRATEGY:-pessimistic}")
    SCENARIOS=("${SCENARIO:-scenario-a}")
    RUNS="${RUNS:-1}"
    export VUS="${VUS:-1}"
    export MIXED_VUS="${MIXED_VUS:-1}"
else
    STRATEGIES=("pessimistic" "optimistic" "distributed")
    SCENARIOS=("scenario-a" "scenario-b" "scenario-c" "scenario-d" "scenario-e" "scenario-f")
    RUNS="${RUNS:-3}"
fi

mkdir -p "$RESULTS_ROOT"

APP_PID=""
cleanup() {
    if [ -n "$APP_PID" ]; then
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

echo "=========================================="
echo " k6 부하 테스트 시작"
echo " BASE_URL: $BASE_URL"
echo " SCHEDULE_ID: $SCHEDULE_ID"
echo " USER_COUNT: $USER_COUNT"
echo " 결과 경로: $RESULTS_ROOT"
echo " 전략: ${STRATEGIES[*]}"
echo " 시나리오: ${SCENARIOS[*]}"
echo " 반복 횟수: $RUNS"
echo "=========================================="

for strategy in "${STRATEGIES[@]}"; do
    STRATEGY_DIR="$RESULTS_ROOT/$strategy"
    mkdir -p "$STRATEGY_DIR"

    echo ""
    echo "══════════════════════════════════════════"
    echo " 전략: $strategy"
    echo "══════════════════════════════════════════"

    echo "[INFO] $strategy 전략으로 앱 시작..."
    cd "$PROJECT_DIR"
    ./gradlew bootRun --args="--reservation.strategy=$strategy" > "$STRATEGY_DIR/app.log" 2>&1 &
    APP_PID=$!

    echo "[INFO] 앱 준비 대기 중..."
    for i in $(seq 1 90); do
        if curl -s -o /dev/null -w "%{http_code}" \
            -X POST "$BASE_URL/api/auth/login" \
            -H "Content-Type: application/json" \
            -d '{}' 2>/dev/null | grep -q "4"; then
            echo "[INFO] 앱 준비 완료 (${i}초)"
            break
        fi
        sleep 1
        if [ "$i" -eq 90 ]; then
            echo "[ERROR] 앱 시작 시간 초과"
            exit 1
        fi
    done

    for scenario in "${SCENARIOS[@]}"; do
        echo ""
        echo "--- $strategy / $scenario ---"

        for run in $(seq 1 "$RUNS"); do
            RUN_DIR="$STRATEGY_DIR/$scenario/run-$run"
            mkdir -p "$RUN_DIR"

            echo "[RUN $run/$RUNS] load-test fixture reset..."
            curl -fsS -X POST "$BASE_URL/api/admin/load-test/reset?scheduleId=$SCHEDULE_ID&userCount=$USER_COUNT" \
                -o "$RUN_DIR/reset.json"
            cat "$RUN_DIR/reset.json"
            echo ""
            sleep 1

            echo "[RUN $run/$RUNS] k6 실행: $scenario"
            k6 run \
                --out json="$RUN_DIR/events.json" \
                --summary-export="$RUN_DIR/summary.json" \
                -e BASE_URL="$BASE_URL" \
                -e SCHEDULE_ID="$SCHEDULE_ID" \
                -e USER_COUNT="$USER_COUNT" \
                "$SCRIPT_DIR/${scenario}.js" 2>&1 | tee "$RUN_DIR/k6.log"

            curl -fsS "$BASE_URL/api/admin/load-test/summary?scheduleId=$SCHEDULE_ID" \
                -o "$RUN_DIR/final-summary.json"

            echo "[RUN $run/$RUNS] 완료: $RUN_DIR"
            sleep 2
        done
    done

    echo "[INFO] $strategy 전략 앱 종료..."
    cleanup
    APP_PID=""
    sleep 3
done

echo ""
echo "=========================================="
echo " 전체 테스트 완료!"
echo " 결과: $RESULTS_ROOT/"
echo "=========================================="
