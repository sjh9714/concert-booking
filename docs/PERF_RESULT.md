# Performance Result

이 문서는 k6로 실제 측정한 값과 아직 측정하지 않은 시나리오를 분리해서 기록합니다. 새 수치는 만들지 않습니다.

## 1. Test Environment

| 항목 | 값 |
| --- | --- |
| CPU | Apple M4 |
| RAM | 16 GB |
| OS | macOS (Darwin) |
| Docker | Docker Desktop, 로컬 컨테이너 |
| PostgreSQL | 16 |
| Redis | 7 |
| Kafka | confluentinc/cp-kafka:7.6.0 |
| Spring Boot | 3.4.1 |
| JVM | OpenJDK 21.0.8 (Temurin), 기본 힙 설정 |
| Tomcat | 기본 설정, max threads 200 |
| HikariCP | 기본 설정, maximum pool size 10 |
| Redisson | 3.40.2 |
| k6 | v1.5.0 |

## 2. Data Setup

k6 스크립트는 같은 fixture에서 시작할 수 있도록 `!prod` profile 전용 load-test reset endpoint를 사용합니다.
일반 `/api/admin/**` utility는 `ROLE_ADMIN` 권한이 필요하지만, `/api/admin/load-test/**`는 로컬 부하 테스트 재현성을 위해 `!prod` profile에서만 인증 없이 노출됩니다.

```bash
POST /api/admin/load-test/reset?scheduleId=1&userCount=200
GET  /api/admin/load-test/summary?scheduleId=1
```

reset이 맞추는 값:

| 항목 | 상태 |
| --- | --- |
| schedule | `scheduleId=1` |
| seats | 50개 `AVAILABLE` |
| `ConcertSchedule.availableSeats` | 50 |
| Redis stock | 50 |
| users | `loadtest-user-{0..N-1}@k6.local`, password `password123` |
| reservations/payments/idempotency/reservation_seats | 대상 schedule 기준 삭제 |
| queue/token/inflight/seat hold key | 대상 schedule 기준 삭제 |

주의: 아래 A/B/C 측정값은 기존 로컬 실행 결과입니다.
결제/만료 race 검증(Scenario D), 중복 요청 idempotency replay/conflict 검증(Scenario E),
대기열 token abuse 검증(Scenario F)은 refined smoke, 단일 targeted run,
세 전략 x 3회 formal local repeat로 branch/threshold를 확인했습니다.
단, 이 결과는 운영 성능, SLO, capacity claim으로 사용하지 않습니다.

## 3. Scenario Status

| 라벨 | 표시명 | 의미 |
| --- | --- | --- |
| measured | 측정 완료 | k6로 수치를 측정했고 아래 표에 기록 |
| verified | 시나리오 검증 | Testcontainers 통합 테스트 또는 제한된 k6 targeted run으로 정책/분기/threshold를 검증 |
| pending | 추가 측정 예정 | formal benchmark나 정식 수치 표로 승격 전 |

| 시나리오 | 파일 | 목적 | 상태 |
| --- | --- | --- | --- |
| A 동일 좌석 경합 | `k6/scenario-a.js` | 동일 좌석 100명 동시 예매 | 측정 완료 |
| B 분산 좌석 예약 | `k6/scenario-b.js` | 50명이 서로 다른 좌석 예매 | 측정 완료 |
| C 혼합 부하 테스트 | `k6/scenario-c.js` | 70% 조회 + 30% 예매 | 측정 완료 |
| 결제/만료 race 검증 (Scenario D) | `k6/scenario-d.js` | 결제와 만료 race | 시나리오 검증 |
| 중복 요청 idempotency replay/conflict 검증 (Scenario E) | `k6/scenario-e.js` | 같은 idempotency key 재요청과 다른 좌석 conflict | 시나리오 검증 |
| 대기열 token abuse 검증 (Scenario F) | `k6/scenario-f.js` | token 없음/타 사용자/타 schedule/만료 token 차단 | 시나리오 검증 |

## 4. Measured Results

### A. Hot Seat Contention

조건: 100 VU, 동일 좌석 1개, per-vu-iterations 1회.

| 메트릭 | 비관적 락 | 낙관적 락 | Redis 분산 락 |
| --- | ---: | ---: | ---: |
| 성공 수 | 1 | 1 | 1 |
| 실패 수 | 99 | 99 | 99 |
| overselling | 0건 | 0건 | 0건 |
| p50 | 68ms | 70ms | 70ms |
| p90 | 202ms | 99ms | 115ms |
| p95 | 215ms | 106ms | 145ms |
| max | 235ms | 123ms | 184ms |

해석: 세 전략 모두 같은 좌석 1개에 대해 하나의 성공만 허용했습니다. 샘플 수가 100건이라 p99는 주장하지 않습니다.

### B. Distributed Reservation

조건: 50 VU, 50개 좌석, 각 VU가 서로 다른 좌석 1개 예매.

| 메트릭 | 비관적 락 | 낙관적 락 | Redis 분산 락 |
| --- | ---: | ---: | ---: |
| 성공률 | 100% (50/50) | 40% (20/50) | 100% (50/50) |
| p50 | 64ms | 200ms | 90ms |
| p90 | 91ms | 215ms | 120ms |
| p95 | 95ms | 215ms | 126ms |
| max | 98ms | 217ms | 132ms |

해석: 낙관적 락은 좌석이 서로 달라도 `ConcertSchedule.availableSeats` 공유 row의 `@Version` 충돌을 겪습니다. 현재 구현은 제한된 retry를 사용하므로 50건 중 20건만 성공했습니다.

### C. Mixed Load

조건: 200 VU, ramping-vus 45초, 70% 좌석 조회 + 30% 예매, 예매는 80% 확률로 인기 좌석 상위 20%에 집중.

| 메트릭 | 비관적 락 | 낙관적 락 | Redis 분산 락 |
| --- | ---: | ---: | ---: |
| 총 RPS | 969 | 993 | 1,005 |
| 읽기 p50 | 3ms | 3ms | 3ms |
| 읽기 p90 | 11ms | 6ms | 5ms |
| 읽기 p95 | 28ms | 9ms | 7ms |
| 쓰기 p50 | 3ms | 3ms | 2ms |
| 쓰기 p90 | 16ms | 7ms | 4ms |
| 쓰기 p95 | 37ms | 10ms | 6ms |
| 읽기 성공 | 48,493 | 50,372 | 50,984 |
| 쓰기 성공 | 50 | 50 | 50 |
| 쓰기 실패 | 20,766 | 21,711 | 21,638 |

해석: 쓰기 성공은 좌석 수만큼 50건입니다. 쓰기 실패는 이미 선점되었거나 예매된 좌석을 다시 시도한 요청입니다.

## 5. 결제/만료, 중복 요청, token abuse 검증 결과

| 시나리오 | 현재 상태 | 수치를 쓰지 않는 이유 |
| --- | --- | --- |
| 결제/만료 race 검증 (Scenario D) | 세 전략 x 3회 formal local repeat checked | race branch/threshold 검증이며 운영 latency/throughput claim으로 사용하지 않음 |
| 중복 요청 idempotency replay/conflict 검증 (Scenario E) | 세 전략 x 3회 formal local repeat checked | idempotency replay/conflict 검증이며 throughput claim으로 사용하지 않음 |
| 대기열 token abuse 검증 (Scenario F) | 세 전략 x 3회 formal local repeat checked | 우회 차단 threshold 검증이며 latency/throughput claim으로 사용하지 않음 |

세 시나리오 formal local repeat는 2026-05-22에 아래 조건으로 실행했습니다.

```bash
RESULTS_ROOT="$PWD/k6/results/20260522-205723-formal-d-e-f" \
STRATEGIES_OVERRIDE="pessimistic optimistic distributed" \
SCENARIOS_OVERRIDE="scenario-d scenario-e scenario-f" \
RUNS=3 \
SCHEDULE_ID=1 \
OTHER_SCHEDULE_ID=2 \
bash k6/run-all.sh
```

| 시나리오 | 범위 | checks | 핵심 해석 |
| --- | --- | ---: | --- |
| 결제/만료 race 검증 (Scenario D) | 3 strategies x 3 runs | 216/216 passed | 모든 run에서 `expected_race_loser: 10`, `unexpected_race_response: 0`, `duplicatePaymentCount: 0` |
| 중복 요청 idempotency replay/conflict 검증 (Scenario E) | 3 strategies x 3 runs | 234/234 passed | 모든 run에서 replay branch 20/20, conflict branch 1, `request_fail: 0`, duplicate row 0 |
| 대기열 token abuse 검증 (Scenario F) | 3 strategies x 3 runs | 144/144 passed | 모든 run에서 `unauthorized_success_count: 0`, `unauthorized_reject_count: 4`, `unexpected_reject_status_count: 0` |

요약 증거는 [docs/evidence/SCENARIO_D_E_F_FORMAL_2026-05-22.md](evidence/SCENARIO_D_E_F_FORMAL_2026-05-22.md)에
분리했습니다. raw `k6/results/...` 디렉터리는 generated artifact이므로 git에는 포함하지 않습니다.

세 시나리오 targeted local run은 2026-05-22에 아래 조건으로 실행했습니다.

```bash
RESULTS_ROOT="$PWD/k6/results/20260522-173952-targeted-d-e-f" \
STRATEGIES_OVERRIDE="pessimistic" \
SCENARIOS_OVERRIDE="scenario-d scenario-e scenario-f" \
RUNS=1 \
SCHEDULE_ID=1 \
OTHER_SCHEDULE_ID=2 \
bash k6/run-all.sh
```

| 시나리오 | checks | 핵심 counter | final domain summary |
| --- | --- | --- | --- |
| 결제/만료 race 검증 (Scenario D) | 24/24 passed | `expected_race_loser: 10`, `unexpected_race_response: 0`, `payment_success: 1`, `expire_success: 9` | `confirmedReservationCount: 1`, `expiredReservationCount: 9`, `paymentCount: 1`, `duplicatePaymentCount: 0` |
| 중복 요청 idempotency replay/conflict 검증 (Scenario E) | 26/26 passed | `duplicate_reservation_response: 20`, `duplicate_payment_response: 20`, `idempotency_conflict_count: 1`, `request_fail: 0` | `reservationCount: 1`, `paymentCount: 1`, `duplicateSeatReservationCount: 0`, `duplicatePaymentCount: 0` |
| 대기열 token abuse 검증 (Scenario F) | 16/16 passed | `unauthorized_success_count: 0`, `unauthorized_reject_count: 4`, `unexpected_reject_status_count: 0`, `normal_success_count: 1` | `reservationCount: 1`, `paymentCount: 0` |

이 targeted run은 `run-all.sh`의 `STRATEGIES_OVERRIDE` / `SCENARIOS_OVERRIDE`로 실행한 단일 local run입니다.
k6 결제/만료 race 검증(Scenario D)은 race branch/threshold와 aggregate final summary 근거입니다.
같은 reservation이 confirmed와
expired를 동시에 갖지 않는 per-reservation 상태 전이 불변식은 `ReservationStateTransitionRaceIntegrationTest`의
검증 범위로 분리합니다. 중복 요청 idempotency replay/conflict 검증(Scenario E)은 same-key replay 응답과
최종 중복 row 부재를 확인하지만, 모든 replay
응답 body가 byte-identical하다는 claim은 하지 않습니다.
요약 증거는 [docs/evidence/SCENARIO_D_E_F_TARGETED_2026-05-22.md](evidence/SCENARIO_D_E_F_TARGETED_2026-05-22.md)에
분리했습니다. raw `k6/results/...` 디렉터리는 generated artifact이므로 git에는 포함하지 않습니다.

결제/만료 race 검증(Scenario D) refined smoke는 2026-05-22에 아래 조건으로 실행했습니다.

```bash
RESULTS_ROOT="$PWD/k6/results/20260522-153959-scenario-d-refined-threshold-smoke" \
SMOKE=1 STRATEGY=pessimistic SCENARIO=scenario-d VUS=20 RUNS=1 USER_COUNT=20 SCHEDULE_ID=1 bash k6/run-all.sh
```

| 항목 | 결과 |
| --- | --- |
| checks | 24/24 passed |
| expected race loser | 10 |
| unexpected race response | 0 |
| payment success count | 0 |
| expiration success count | 10 |
| duplicate payment suspect | 0 |
| final confirmed reservations | 0 |
| final expired reservations | 10 |

이 smoke는 결제/만료 race path가 실행되는지와 예상 가능한 race-loser 응답이 예상 밖 응답과 분리되는지
확인한 기록입니다. `unexpected_race_response == 0` threshold를 통과했지만, 작은 local smoke이므로
throughput, latency, error-rate claim이나 정식 부하 결과로 사용하지 않습니다. 요약 증거는
[docs/evidence/SCENARIO_D_SMOKE_2026-05-22.md](evidence/SCENARIO_D_SMOKE_2026-05-22.md)에 분리했습니다.

대기열 token abuse 검증(Scenario F) smoke는 2026-05-22에 아래 조건으로 확인했습니다.

```bash
SMOKE=1 STRATEGY=pessimistic SCENARIO=scenario-f VUS=5 RUNS=1 USER_COUNT=4 SCHEDULE_ID=1 OTHER_SCHEDULE_ID=2 bash k6/run-all.sh
```

| 항목 | 결과 |
| --- | --- |
| missing token | rejected |
| other user token | rejected |
| other schedule token | rejected |
| expired token | rejected |
| unauthorized success count | 0 |
| unauthorized reject count | 4 |
| unexpected reject status count | 0 |
| normal success count | 1 |
| checks | 16/16 passed |

이 smoke는 각 abuse branch가 최소 한 번씩 실행되는지와 `unauthorized_success_count == 0`,
`unexpected_reject_status_count == 0` threshold를 확인한 결과입니다. 기존 VUS=1 smoke나 이 작은
smoke를 정식 부하 결과로 사용하지 않습니다.
요약 증거는 [docs/evidence/SCENARIO_F_SMOKE_2026-05-22.md](evidence/SCENARIO_F_SMOKE_2026-05-22.md)에
분리했습니다.

중복 요청 idempotency replay/conflict 검증(Scenario E) smoke는 2026-05-22에 아래 조건으로 확인했습니다.

```bash
SMOKE=1 STRATEGY=pessimistic SCENARIO=scenario-e VUS=5 RUNS=1 USER_COUNT=4 SCHEDULE_ID=1 bash k6/run-all.sh
```

| 항목 | 결과 |
| --- | --- |
| same-key successful reservation responses | 5 |
| same-key successful payment responses | 5 |
| same-key different-seat conflict count | 1 |
| request fail count | 0 |
| duplicate seat reservation suspect | 0 |
| duplicate payment suspect | 0 |
| checks | 11/11 passed |

`same-key successful ... responses`는 최초 생성/결제 응답과 같은 key replay 응답을 합친 branch count입니다.
중복 row가 없다는 결론은 final domain summary의 `reservationCount: 1`, `paymentCount: 1`,
`duplicateSeatReservationCount: 0`, `duplicatePaymentCount: 0`을 함께 확인해 해석합니다.
이 k6 evidence는 byte-identical 응답 body를 주장하지 않고, duplicate-row prevention을 지지하는 근거로만
사용합니다.

이 smoke는 idempotency replay와 같은 key로 다른 좌석을 요청하는 conflict branch가 최소 한 번 실행되는지 확인한
결과입니다. 작은 local smoke이므로 throughput, latency, error-rate claim으로 사용하지 않습니다. 요약 증거는
[docs/evidence/SCENARIO_E_SMOKE_2026-05-22.md](evidence/SCENARIO_E_SMOKE_2026-05-22.md)에 분리했습니다.

## 6. Verified By Integration Tests

성능 수치와 별개로 다음 정책은 Testcontainers 통합 테스트로 검증했습니다.

| 검증 | 대표 테스트 |
| --- | --- |
| Hot seat overselling 방지 | `ConcurrencyIntegrationTest`, `DistributedLockConcurrencyTest`, `OptimisticLockConcurrencyTest` |
| 입장 토큰 검증/소비 정책 | `QueueTokenPolicyIntegrationTest` |
| 예매 idempotency | `ReservationIdempotencyIntegrationTest` |
| 결제 idempotency | `PaymentIdempotencyIntegrationTest` |
| 결제/취소/만료 race | `ReservationStateTransitionRaceIntegrationTest` |
| 좌석 반환 멱등성 | `SeatReleaseIdempotencyIntegrationTest` |
| Outbox relay 실패/재시도 | `OutboxIntegrationTest` |
| Kafka DLT/replay | `KafkaDltReplayIntegrationTest` |
| Redis stock reconciliation | `StockReconciliationIntegrationTest` |
| load-test fixture reset | `LoadTestAdminControllerIntegrationTest` |

## 7. Analysis

### 낙관적 락 성공률이 낮은 이유

Scenario B에서 각 사용자는 다른 좌석을 예매합니다. 그래도 모든 예매는 같은 `ConcertSchedule.availableSeats`를 감소시킵니다. 낙관적 락은 이 공유 row의 version 충돌을 커밋 시점에 감지합니다.

retry 횟수를 크게 늘리면 성공률은 올라갈 수 있습니다. 대신 p95 응답 시간과 DB 부하도 같이 증가합니다. 이 결과는 낙관적 락이 무조건 부적합하다는 뜻이 아니라, 공유 카운터 row가 있는 모델에서는 충돌 비용이 쉽게 드러난다는 뜻입니다.

### Redis 분산 락이 실패 요청에서 빠른 이유

Redis 분산 락 전략은 DB transaction 전에 Redis stock을 먼저 감소시킵니다. 좌석이 이미 소진된 요청은 DB connection을 잡지 않고 실패합니다. Mixed Load처럼 소진 이후에도 쓰기 요청이 계속 들어오는 시나리오에서는 이 차이가 쓰기 p95에 반영됩니다.

단, Redis stock은 최종 기준 데이터가 아닙니다. 장애나 중복 이벤트 이후 Redis stock, `ConcertSchedule.availableSeats`, 실제 `Seat.status`가 어긋날 수 있으므로 manual reconciliation utility를 둡니다.

### 전략별 해석

| 전략 | 잘 맞는 지점 | 조심할 지점 |
| --- | --- | --- |
| 비관적 락 | 높은 경합에서 결과가 예측 가능 | lock wait와 connection 점유 |
| 낙관적 락 | 낮은 충돌, 읽기 많은 흐름 | 공유 row version 충돌과 retry 비용 |
| Redis 분산 락 | 실패 요청을 DB 전에 빠르게 차단 | Redis 의존성, stock 보정 필요 |

## 8. How To Re-run

```bash
docker compose up -d
./gradlew bootRun --args="--reservation.strategy=distributed"
curl -X POST "http://localhost:8080/api/admin/load-test/reset?scheduleId=1&userCount=200"
k6 run k6/scenario-a.js
```

전체 실행:

```bash
bash k6/run-all.sh
```

결과 경로:

```text
k6/results/{timestamp}/{strategy}/{scenario}/run-{n}/
├── reset.json
├── summary.json
├── events.json
├── k6.log
└── final-summary.json
```

## 9. Limitations

- 로컬 Docker 기준입니다. 실제 운영 환경 수치가 아닙니다.
- A/B/C는 단일 실행 결과입니다. 평균, 표준편차, 신뢰구간을 계산하지 않았습니다.
- JVM warmup을 별도로 두지 않았습니다.
- A/B는 샘플 수가 작아 p99를 주장하지 않습니다.
- HikariCP와 Tomcat thread pool은 기본 설정입니다.
- PostgreSQL, Redis, Kafka, 애플리케이션이 같은 머신에서 실행되었습니다.
- 결제는 mock payment 즉시 성공 구조입니다. 외부 PG latency, 승인 실패, webhook 흐름은 포함하지 않습니다.
- 결제/만료 race 검증(Scenario D), 중복 요청 idempotency replay/conflict 검증(Scenario E),
  대기열 token abuse 검증(Scenario F)은 refined smoke, pessimistic 단일 targeted run,
  세 전략 x 3회 formal local repeat로 branch/threshold를 확인했습니다.
  운영 성능 claim과 장기 반복 신뢰구간은 추가 측정 예정입니다.
