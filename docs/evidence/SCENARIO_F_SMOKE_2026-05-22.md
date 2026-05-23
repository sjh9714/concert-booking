# 대기열 token abuse 검증 (Scenario F) Smoke Evidence

이 문서는 `docs/PERF_RESULT.md`의 대기열 token abuse 검증(Scenario F) smoke 표를 뒷받침하는
요약 증거입니다. 정식 부하 테스트 결과가 아니라 queue token abuse 분기가 모두 거부되는지 확인한
작은 local smoke입니다.

## Command

```bash
SMOKE=1 STRATEGY=pessimistic SCENARIO=scenario-f VUS=5 RUNS=1 USER_COUNT=4 SCHEDULE_ID=1 OTHER_SCHEDULE_ID=2 bash k6/run-all.sh
```

## Scope

- missing token
- other user token
- other schedule token
- expired token
- normal token request

## k6 Summary

```txt
checks_total: 16
checks_succeeded: 16 out of 16
checks_failed: 0 out of 16
unauthorized_success_count: 0
unauthorized_reject_count: 4
unexpected_reject_status_count: 0
normal_success_count: 1
```

Raw local output was refreshed at `k6/results/20260522-165610/pessimistic/scenario-f/run-1/`.
The result directory is generated evidence and is not committed; the counters above are copied from
`summary.json`.

## Final Domain Summary

```json
{
  "scheduleId": 1,
  "totalSeats": 50,
  "availableSeatCount": 49,
  "heldSeatCount": 1,
  "reservedSeatCount": 0,
  "scheduleAvailableSeats": 49,
  "redisStock": 50,
  "reservationCount": 1,
  "pendingReservationCount": 1,
  "confirmedReservationCount": 0,
  "cancelledReservationCount": 0,
  "expiredReservationCount": 0,
  "paymentCount": 0,
  "duplicateSeatReservationCount": 0,
  "duplicatePaymentCount": 0
}
```

## Interpretation

- `unauthorized_success_count == 0`이므로 abuse request가 예약 성공으로 통과하지 않았습니다.
- `unexpected_reject_status_count == 0`이므로 abuse request가 예상하지 않은 status로 거부되지
  않았습니다.
- `normal_success_count == 1`은 정상 token 요청 경로가 smoke에서 최소 한 번 통과했음을 뜻합니다.
- pessimistic 전략 smoke에서 `redisStock`은 최종 판정 기준이 아닙니다. 좌석/예약 상태는 DB 기준
  summary로 해석하고, Redis stock 불일치는 reconciliation 대상으로 봅니다.
- 이 결과는 branch coverage와 threshold 확인용입니다. p95 latency, throughput, 운영 성능 claim으로
  사용하지 않습니다.
