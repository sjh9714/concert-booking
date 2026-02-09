#!/bin/bash
#
# k6 부하 테스트 자동 실행 스크립트
# 3가지 전략 × 3가지 시나리오 × 3회 반복
#
# 사용법:
#   chmod +x k6/run-all.sh
#   bash k6/run-all.sh
#
# 사전 조건:
#   1. Docker Compose 인프라 실행 (docker compose up -d)
#   2. k6 설치 (brew install k6)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="$PROJECT_DIR/k6/results"
SCHEDULE_ID="${SCHEDULE_ID:-1}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

STRATEGIES=("pessimistic" "optimistic" "distributed")
SCENARIOS=("scenario-a" "scenario-b" "scenario-c")
RUNS=3

mkdir -p "$RESULTS_DIR"

echo "=========================================="
echo " k6 부하 테스트 시작"
echo " BASE_URL: $BASE_URL"
echo " SCHEDULE_ID: $SCHEDULE_ID"
echo " 전략: ${STRATEGIES[*]}"
echo " 시나리오: ${SCENARIOS[*]}"
echo " 반복 횟수: $RUNS"
echo "=========================================="

for strategy in "${STRATEGIES[@]}"; do
    echo ""
    echo "══════════════════════════════════════════"
    echo " 전략: $strategy"
    echo "══════════════════════════════════════════"

    # 앱 시작 (백그라운드)
    echo "[INFO] $strategy 전략으로 앱 시작..."
    cd "$PROJECT_DIR"
    ./gradlew bootRun -Dreservation.strategy="$strategy" > "$RESULTS_DIR/${strategy}_app.log" 2>&1 &
    APP_PID=$!

    # 앱 준비 대기 (최대 60초)
    echo "[INFO] 앱 준비 대기 중..."
    for i in $(seq 1 60); do
        if curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/auth/login" 2>/dev/null | grep -q "4"; then
            echo "[INFO] 앱 준비 완료 (${i}초)"
            break
        fi
        sleep 1
        if [ "$i" -eq 60 ]; then
            echo "[ERROR] 앱 시작 시간 초과"
            kill "$APP_PID" 2>/dev/null || true
            exit 1
        fi
    done

    for scenario in "${SCENARIOS[@]}"; do
        echo ""
        echo "--- $strategy / $scenario ---"

        for run in $(seq 1 $RUNS); do
            echo "[RUN $run/$RUNS] 데이터 리셋..."
            curl -s -X POST "$BASE_URL/api/admin/reset?scheduleId=$SCHEDULE_ID" > /dev/null
            sleep 1

            RESULT_FILE="$RESULTS_DIR/${strategy}_${scenario}_run${run}.json"
            echo "[RUN $run/$RUNS] k6 실행: $scenario"

            k6 run \
                --out json="$RESULT_FILE" \
                --summary-export="$RESULTS_DIR/${strategy}_${scenario}_run${run}_summary.json" \
                -e BASE_URL="$BASE_URL" \
                -e SCHEDULE_ID="$SCHEDULE_ID" \
                -e CONCERT_ID=1 \
                "$SCRIPT_DIR/${scenario}.js" 2>&1 | tee "$RESULTS_DIR/${strategy}_${scenario}_run${run}.log"

            echo "[RUN $run/$RUNS] 완료"
            sleep 2
        done
    done

    # 앱 종료
    echo "[INFO] $strategy 전략 앱 종료..."
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
    sleep 3
done

echo ""
echo "=========================================="
echo " 전체 테스트 완료!"
echo " 결과: $RESULTS_DIR/"
echo "=========================================="
