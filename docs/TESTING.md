# Testing Evidence

이 문서는 Concert Booking의 포트폴리오 claim을 어떤 테스트가 지지하는지와 아직 claim하지 않는 범위를
분리합니다. 새 수치는 추가하지 않습니다.

## 검증된 범위

| 범위 | 대표 테스트 / 도구 | 검증하는 주장 |
| --- | --- | --- |
| 동일 좌석 overselling 방지 | `ConcurrencyIntegrationTest`, `DistributedLockConcurrencyTest`, `OptimisticLockConcurrencyTest` | 동일 좌석 경합에서 하나의 성공만 허용 |
| schedule-bound seat validation | `SeatScheduleValidationIntegrationTest` | 요청 schedule에 속하지 않는 좌석 예매 차단 |
| queue token 정책 | `QueueTokenPolicyIntegrationTest` | token 필수, userId + scheduleId 바인딩, 성공 후 소비 |
| reservation/payment idempotency | `ReservationIdempotencyIntegrationTest`, `PaymentIdempotencyIntegrationTest` | timeout retry와 더블클릭 중복 요청 흡수 |
| 결제/취소/만료 race | `ReservationStateTransitionRaceIntegrationTest` | reservation row lock과 도메인 상태 전이 guard |
| Outbox relay / retry | `OutboxIntegrationTest` | DB commit 이후 event 발행 의도 저장과 retry 상태 |
| Kafka DLT / replay | `KafkaDltReplayIntegrationTest` | consumer 실패 격리와 manual replay 경로 |
| Redis stock reconciliation | `StockReconciliationIntegrationTest` | Redis를 최종 기준 데이터로 두지 않고 DB 기준 보정 |
| Actuator endpoint access policy | `ActuatorSecurityIntegrationTest` | health/info 공개와 metrics/prometheus 보호 정책 |
| Metric registration/classification | `BookingMetricsTest` | reservation, queue token, Outbox, reconciliation metric 분류 |
| Outbox gauge scheduled refresh | `OutboxMetricsSnapshotSchedulingIntegrationTest` | scrape 시점 DB 조회 대신 scheduled snapshot refresh |
| Prometheus actuator metric contract | `PrometheusScrapeContractIntegrationTest` | alert rule과 dashboard가 참조하는 metric name이 보호된 `/actuator/prometheus` 응답에 노출되는지 검증 |
| Monitoring template syntax | CI `Check monitoring template syntax` | Prometheus config와 Grafana dashboard JSON 파싱 가능성 |
| Alert rule expression unit test | `promtool test rules monitoring/alert-rules.test.yml` | synthetic time series에서 Outbox DEAD, Redis stock mismatch, queue token failure alert expression과 annotation 검증 |
| Local Prometheus server scrape artifact | `docs/evidence/monitoring/prometheus-20260522T155512Z/capture-summary.json` | local Prometheus target 1개 `up`, 필수 alert rule 로딩, Outbox query series 존재 검산 |
| Local Prometheus evidence capture validator | `python3 scripts/test-monitoring-evidence-validator.py` | 실제 Prometheus server scrape artifact가 target/rule/query와 claim boundary를 갖추었는지 검산하는 도구 |
| Local monitoring harness syntax | `bash -n scripts/monitoring-local-verify.sh`, `docker compose -f docker-compose.yml -f docker-compose.monitoring.yml config`, `promtool check config monitoring/prometheus.local.yml` | local-only admin JWT 기반 Prometheus/Grafana harness가 파싱 가능한지 검증 |
| k6 A/B/C | `k6/scenario-a.js`, `k6/scenario-b.js`, `k6/scenario-c.js` | 로컬 Docker 기준 측정 완료 수치 |
| 세 시나리오 formal local repeat | `k6/scenario-d.js`, `k6/scenario-e.js`, `k6/scenario-f.js`, `docs/evidence/SCENARIO_D_E_F_FORMAL_2026-05-22.md` | 세 전략 x 3회 반복으로 branch/threshold 시나리오 검증 |
| 세 시나리오 smoke + targeted local run | `k6/scenario-d.js`, `k6/scenario-e.js`, `k6/scenario-f.js` | formal repeat 이전 branch smoke와 pessimistic 단일 targeted run 기록 |
| 중복 요청 idempotency replay/conflict 검증 (Scenario E) smoke | `k6/scenario-e.js` | 같은 key replay와 다른 좌석 conflict branch smoke |
| 대기열 token abuse 검증 (Scenario F) smoke | `k6/scenario-f.js` | token 없음/타 사용자/타 schedule/만료 token 우회 차단 branch smoke |

## 아직 검증하지 않는 범위

| 범위 | 현재 상태 |
| --- | --- |
| 세 시나리오 운영 성능 통계 | 세 전략 x 3회 formal local repeat는 보존했지만 운영 latency/throughput/error-rate claim으로 사용하지 않음 |
| 세 시나리오 신뢰구간/장기 반복 통계 | 3회 local repeat를 넘어선 신뢰구간, 장기 반복, capacity claim은 아직 추가 측정 예정 |
| 대기열 token abuse 검증 (Scenario F) 부하 성능 | branch/threshold 검증은 보존했지만 latency/throughput claim으로 사용하지 않음 |
| alert/dashboard/tracing/SLO 운영 체계 | actuator metric contract, local template syntax, synthetic alert rule test 수준이며 운영 claim으로 확장하지 않음 |
| 다회 반복 통계 | 기존 k6 수치는 단일 로컬 실행 기준이며 평균/표준편차/신뢰구간을 주장하지 않음 |

## 실행 명령

```bash
./gradlew test --no-daemon
./gradlew build --no-daemon
k6 inspect k6/scenario-f.js
k6 inspect k6/scenario-e.js
bash -n scripts/capture-monitoring-evidence.sh
bash -n scripts/monitoring-local-verify.sh
python3 scripts/test-monitoring-evidence-validator.py
```

## 해석 원칙

- k6 formal local repeat는 branch/threshold 확인과 운영 성능 수치를 분리합니다.
- Testcontainers 통합 테스트는 정책 검증 근거이며 throughput/latency 측정 근거가 아닙니다.
- Prometheus actuator metric contract는 dashboard/alert expression과 actuator output의 metric name 계약 검증입니다.
- `docs/evidence/monitoring/prometheus-20260522T155512Z/capture-summary.json`은 local Prometheus server scrape artifact입니다. target/rule/query 존재만 검산하며 alert firing, dashboard render, tracing, SLO 운영 증거로 해석하지 않습니다.
- `local-monitoring` profile과 `docker-compose.monitoring.yml`은 로컬에서 ADMIN JWT 기반 Prometheus scrape를 실험하기 위한 harness입니다. production auth, alert routing, Grafana 운영 증거로 해석하지 않습니다.
- Redis stock은 빠른 보조 상태이며, 최종 기준 데이터는 DB seat 상태입니다.
