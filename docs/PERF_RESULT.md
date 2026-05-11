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

주의: 아래 A/B/C 측정값은 기존 로컬 실행 결과입니다. 6차 수정으로 k6 fixture reset과 D/E/F script를 추가했지만, D/E/F 정식 부하 수치는 아직 측정하지 않았습니다.

## 3. Scenario Status

| 라벨 | 의미 |
| --- | --- |
| Measured | k6로 수치를 측정했고 아래 표에 기록 |
| Verified | Testcontainers 통합 테스트로 정책을 검증 |
| Designed | 코드 경로와 script는 있으나 k6 수치 표는 없음 |
| Pending | script만 추가했고 정식 수치 측정 전 |

| 시나리오 | 파일 | 목적 | 상태 |
| --- | --- | --- | --- |
| A Hot Seat Contention | `k6/scenario-a.js` | 동일 좌석 100명 동시 예매 | Measured |
| B Distributed Reservation | `k6/scenario-b.js` | 50명이 서로 다른 좌석 예매 | Measured |
| C Mixed Load | `k6/scenario-c.js` | 70% 조회 + 30% 예매 | Measured |
| D Payment Expiration Race | `k6/scenario-d.js` | 결제와 만료 race | Pending |
| E Duplicate Request / Idempotency | `k6/scenario-e.js` | 같은 idempotency key 재요청 | Pending |
| F Queue Token Abuse | `k6/scenario-f.js` | token 없음/타 사용자/만료 token 차단 | Pending |

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

## 5. Pending k6 Results

| 시나리오 | 현재 상태 | 수치를 쓰지 않는 이유 |
| --- | --- | --- |
| D Payment Expiration Race | script added, result pending | 정식 k6 실행 전 |
| E Duplicate Request / Idempotency | script added, result pending | 정식 k6 실행 전 |
| F Queue Token Abuse | script added, smoke checked | smoke는 문법/기본 흐름 확인용이라 부하 수치로 쓰지 않음 |

Scenario F는 `SMOKE=1 STRATEGY=pessimistic SCENARIO=scenario-f VUS=1 RUNS=1 USER_COUNT=4 bash k6/run-all.sh`로 한 번 실행해 `unauthorized_success_count == 0` threshold를 확인했습니다. 이 값은 정식 부하 결과로 사용하지 않습니다.

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

단, Redis stock은 최종 진실이 아닙니다. 장애나 중복 이벤트 이후 Redis stock, `ConcertSchedule.availableSeats`, 실제 `Seat.status`가 어긋날 수 있으므로 manual reconciliation utility를 둡니다.

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
- D/E/F의 정식 부하 결과는 pending입니다.
