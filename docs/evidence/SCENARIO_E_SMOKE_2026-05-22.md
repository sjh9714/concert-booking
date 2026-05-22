# Scenario E Duplicate Request / Idempotency Smoke Evidence

이 문서는 `docs/PERF_RESULT.md`의 Scenario E smoke 표를 뒷받침하는 요약 증거입니다. 정식 부하 테스트
결과가 아니라 idempotency replay와 conflict branch가 작은 local smoke에서 통과하는지 확인한 기록입니다.

## Command

```bash
SMOKE=1 STRATEGY=pessimistic SCENARIO=scenario-e VUS=5 RUNS=1 USER_COUNT=4 SCHEDULE_ID=1 bash k6/run-all.sh
```

## Scope

- 같은 reservation Idempotency-Key + 같은 좌석 replay
- 같은 payment Idempotency-Key replay
- 같은 reservation Idempotency-Key + 다른 좌석 conflict
- 최종 domain summary의 duplicate suspect count 확인

## k6 Summary

```txt
checks_total: 11
checks_succeeded: 11 out of 11
checks_failed: 0 out of 11
duplicate_reservation_response: 5
duplicate_payment_response: 5
idempotency_conflict_count: 1
request_fail: 0
threshold idempotency_conflict_count count>=1: passed
threshold request_fail count==0: passed
```

## Final Domain Summary

```json
{
  "scheduleId": 1,
  "totalSeats": 50,
  "availableSeatCount": 49,
  "heldSeatCount": 0,
  "reservedSeatCount": 1,
  "scheduleAvailableSeats": 49,
  "redisStock": 50,
  "reservationCount": 1,
  "pendingReservationCount": 0,
  "confirmedReservationCount": 1,
  "cancelledReservationCount": 0,
  "expiredReservationCount": 0,
  "paymentCount": 1,
  "duplicateSeatReservationCount": 0,
  "duplicatePaymentCount": 0
}
```

## Interpretation

- `duplicate_reservation_response: 5`는 최초 생성 응답과 같은 key + 같은 payload replay 응답을 합친 branch count입니다.
- `duplicate_payment_response: 5`는 최초 결제 응답과 같은 payment key replay 응답을 합친 branch count입니다.
- `idempotency_conflict_count: 1`은 같은 reservation key로 다른 좌석을 요청하면 conflict로 거부되는 branch를 확인한
  결과입니다.
- 중복 row가 없다는 결론은 `reservationCount: 1`, `paymentCount: 1`, final summary의 duplicate suspect 0건을 함께 확인해 해석합니다.
- `request_fail: 0`과 final summary의 duplicate suspect 0건을 함께 확인했습니다.
- k6의 `http_req_failed`에는 의도된 409 conflict 응답이 포함될 수 있으므로, 이 smoke에서는 `request_fail`과
  domain summary를 기준으로 해석합니다.
- 이 결과는 branch coverage와 threshold 확인용입니다. p95 latency, throughput, error-rate, 운영 성능 claim으로
  사용하지 않습니다.
