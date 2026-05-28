# Concert Scenario Targeted Local Run Evidence

## Scope

This document summarizes one local targeted run for 결제/만료 race 검증
(Scenario D), 중복 요청 idempotency replay/conflict 검증(Scenario E), and
대기열 token abuse 검증(Scenario F) on the `pessimistic` strategy.
It is stronger than the earlier branch-covering smoke records because the three
scenarios were run together through `k6/run-all.sh` without
`SMOKE=1`, but it is still not a repeated benchmark, all-strategy comparison,
throughput claim, latency claim, or production performance claim; claim boundary: local scenario evidence only.

## Command

```bash
RESULTS_ROOT="$PWD/k6/results/20260522-173952-targeted-d-e-f" \
STRATEGIES_OVERRIDE="pessimistic" \
SCENARIOS_OVERRIDE="scenario-d scenario-e scenario-f" \
RUNS=1 \
SCHEDULE_ID=1 \
OTHER_SCHEDULE_ID=2 \
bash k6/run-all.sh
```

## Raw Local Artifacts

The generated result directory was created in the local workspace and is ignored
by git. The raw files are not committed, so this markdown file should be treated
as summary-only evidence unless the raw directory is archived separately.

```txt
k6/results/20260522-173952-targeted-d-e-f/pessimistic/
├── app.log
├── scenario-d/run-1/
│   ├── events.json
│   ├── final-summary.json
│   ├── k6.log
│   ├── reset.json
│   └── summary.json
├── scenario-e/run-1/
│   ├── events.json
│   ├── final-summary.json
│   ├── k6.log
│   ├── reset.json
│   └── summary.json
└── scenario-f/run-1/
    ├── events.json
    ├── final-summary.json
    ├── k6.log
    ├── reset.json
    └── summary.json
```

## 결제/만료 race 검증 (Scenario D)

| Item | Observed |
| --- | ---: |
| checks | 24/24 passed |
| expected_race_loser | 10 |
| unexpected_race_response | 0 |
| payment_success | 1 |
| expire_success | 9 |
| reservationCount | 10 |
| confirmedReservationCount | 1 |
| expiredReservationCount | 9 |
| paymentCount | 1 |
| duplicatePaymentCount | 0 |

Interpretation:

- The run created 10 pending reservations, then raced payment and expiration
  across 20 VUs.
- One payment path won and nine expiration paths won in this local run.
- `expected_race_loser: 10` and `unexpected_race_response: 0` show that
  race-loser responses were classified rather than mixed with unexpected states.
- Per-reservation state transition invariants are covered by
  `ReservationStateTransitionRaceIntegrationTest`; this k6 run is aggregate
  branch/threshold evidence.
- This is branch/threshold evidence only.

## 중복 요청 idempotency replay/conflict 검증 (Scenario E)

| Item | Observed |
| --- | ---: |
| checks | 26/26 passed |
| duplicate_reservation_response | 20 |
| duplicate_payment_response | 20 |
| idempotency_conflict_count | 1 |
| request_fail | 0 |
| reservationCount | 1 |
| paymentCount | 1 |
| duplicateSeatReservationCount | 0 |
| duplicatePaymentCount | 0 |

Interpretation:

- Same-key same-payload reservation/payment replay returned successful
  responses without creating duplicate rows.
- Same-key different-seat reservation produced the intended conflict branch.
- Final domain summary keeps the duplicate-row interpretation separate from
  k6 HTTP status accounting.
- This evidence does not claim byte-identical replay response bodies.
- This is idempotency branch/threshold evidence only.

## 대기열 token abuse 검증 (Scenario F)

| Item | Observed |
| --- | ---: |
| checks | 16/16 passed |
| unauthorized_success_count | 0 |
| unauthorized_reject_count | 4 |
| unexpected_reject_status_count | 0 |
| normal_success_count | 1 |
| reservationCount | 1 |
| paymentCount | 0 |

Interpretation:

- Missing token, other user token, other schedule token, and expired token
  branches were rejected.
- `unauthorized_success_count: 0` confirms no abuse branch created a
  reservation in this run.
- `normal_success_count: 1` confirms the normal token path still passed once.
- This is queue-token abuse branch/threshold evidence only.

## Remaining Limits

- Only the `pessimistic` strategy was executed in this targeted run.
- This was a single local run, so it does not provide repeated statistics,
  confidence intervals, or strategy comparison for the D/E/F scenarios.
- The result must not be described as production performance evidence.
