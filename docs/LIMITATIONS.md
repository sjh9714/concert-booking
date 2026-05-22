# Limitations

이 문서는 Concert Booking이 아직 주장하지 않는 것을 분리합니다. 새 수치나 운영 claim은 실제 검증 뒤에만
추가합니다.

## 현재 주장하지 않는 것

| 항목 | 현재 상태 | 다음 보강 |
| --- | --- | --- |
| Scenario D/E/F 장기 반복 통계 | 세 전략 x 3회 formal local repeat는 보존했지만 평균/표준편차/신뢰구간은 주장하지 않음 | 동일 fixture에서 더 긴 반복 실행과 raw output, 환경, 해석을 함께 보존 |
| Scenario D/E/F 운영 성능 비교 | D/E/F는 branch/threshold 시나리오 검증으로만 보존 | 운영 latency/throughput/error-rate를 주장하려면 별도 조건과 반복 통계를 보존 |
| Scenario F 부하 성능 | queue token abuse는 branch/threshold 검증 중심으로 보존 | 더 큰 VU 조건에서 unauthorized success 0과 latency를 함께 기록 |
| 운영형 observability | Actuator metric contract, local Prometheus/Grafana template, synthetic alert rule test, local monitoring harness, local Prometheus scrape artifact(`docs/evidence/monitoring/prometheus-20260522T155512Z/capture-summary.json`) 수준 | alert firing, dashboard screenshot, runbook drill 결과 보존 |
| Outbox exactly-once | 주장하지 않음 | consumer idempotency와 replay 감사 로그를 더 강화 |
| Redis 최종 기준 데이터 | 주장하지 않음 | reconciliation 결과와 mismatch 원인별 복구 기록을 보존 |
| production 성능 | 로컬 Docker 단일 실행 결과만 보존 | 반복 실행, 실행 환경 고정, 신뢰구간 기록 |

## 면접에서 안전하게 말할 문장

> 이 프로젝트는 동일 좌석 경합, queue token, idempotency, Outbox/DLT, Redis reconciliation을 검증한 예매
> 정합성 포트폴리오입니다. k6 D/E/F는 세 전략 x 3회 local repeat로 branch/threshold를 검증했지만,
> Prometheus actuator metric contract는 dashboard/alert template과 actuator output의 metric name 계약 검증이고,
> synthetic alert rule test는 rule expression 검증입니다. `prometheus-20260522T155512Z` artifact는 local
> Prometheus server가 앱을 scrape하고 필수 rule/query를 노출한 증거이지만, alert firing, dashboard 운영,
> tracing, SLO, 운영 성능 claim은 추가 측정 예정으로 분리했습니다.
