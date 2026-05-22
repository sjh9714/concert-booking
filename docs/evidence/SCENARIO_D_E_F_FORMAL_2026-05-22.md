# Scenario D/E/F Formal Local Repeat Evidence

## Scope

This document summarizes the local formal repeat run for Scenario D/E/F on
2026-05-22.

It is stronger than the earlier smoke and single-strategy targeted records
because each scenario was executed against all three reservation strategies
(`pessimistic`, `optimistic`, `distributed`) with three repeats per strategy.

It is still local scenario evidence. It must not be described as production
throughput, latency, error-rate, SLO, or capacity evidence.

## Command

```bash
RESULTS_ROOT="$PWD/k6/results/20260522-205723-formal-d-e-f" \
STRATEGIES_OVERRIDE="pessimistic optimistic distributed" \
SCENARIOS_OVERRIDE="scenario-d scenario-e scenario-f" \
RUNS=3 \
SCHEDULE_ID=1 \
OTHER_SCHEDULE_ID=2 \
bash k6/run-all.sh
```

## Raw Local Artifacts

The generated result directory is ignored by git and remains a local artifact.
This document preserves the curated summary only.

```txt
k6/results/20260522-205723-formal-d-e-f/
├── pessimistic/
├── optimistic/
└── distributed/
    ├── app.log
    ├── scenario-d/run-1..3/{reset,summary,events,k6,final-summary}
    ├── scenario-e/run-1..3/{reset,summary,events,k6,final-summary}
    └── scenario-f/run-1..3/{reset,summary,events,k6,final-summary}
```

## Aggregate Result

| Scenario | Scope | Checks |
| --- | --- | ---: |
| D Payment Expiration Race | 3 strategies x 3 runs | 216/216 passed |
| E Duplicate Request / Idempotency | 3 strategies x 3 runs | 234/234 passed |
| F Queue Token Abuse | 3 strategies x 3 runs | 144/144 passed |

## Scenario D: Payment Expiration Race

| Strategy | Run | Checks | Key counters | Final domain summary |
| --- | ---: | ---: | --- | --- |
| pessimistic | 1 | 24/24 | `expected_race_loser: 10`, `unexpected: 0`, `payment_success: 4`, `expire_success: 6` | `confirmed: 4`, `expired: 6`, `payments: 4`, `duplicatePaymentCount: 0`, `redisStock: 50` |
| pessimistic | 2 | 24/24 | `expected_race_loser: 10`, `unexpected: 0`, `payment_success: 1`, `expire_success: 9` | `confirmed: 1`, `expired: 9`, `payments: 1`, `duplicatePaymentCount: 0`, `redisStock: 50` |
| pessimistic | 3 | 24/24 | `expected_race_loser: 10`, `unexpected: 0`, `payment_success: 1`, `expire_success: 9` | `confirmed: 1`, `expired: 9`, `payments: 1`, `duplicatePaymentCount: 0`, `redisStock: 50` |
| optimistic | 1 | 24/24 | `expected_race_loser: 10`, `unexpected: 0`, `payment_success: 1`, `expire_success: 9` | `confirmed: 1`, `expired: 9`, `payments: 1`, `duplicatePaymentCount: 0`, `redisStock: 50` |
| optimistic | 2 | 24/24 | `expected_race_loser: 10`, `unexpected: 0`, `payment_success: 3`, `expire_success: 7` | `confirmed: 3`, `expired: 7`, `payments: 3`, `duplicatePaymentCount: 0`, `redisStock: 50` |
| optimistic | 3 | 24/24 | `expected_race_loser: 10`, `unexpected: 0`, `payment_success: 0`, `expire_success: 10` | `confirmed: 0`, `expired: 10`, `payments: 0`, `duplicatePaymentCount: 0`, `redisStock: 50` |
| distributed | 1 | 24/24 | `expected_race_loser: 10`, `unexpected: 0`, `payment_success: 2`, `expire_success: 8` | `confirmed: 2`, `expired: 8`, `payments: 2`, `duplicatePaymentCount: 0`, `redisStock: 40` |
| distributed | 2 | 24/24 | `expected_race_loser: 10`, `unexpected: 0`, `payment_success: 0`, `expire_success: 10` | `confirmed: 0`, `expired: 10`, `payments: 0`, `duplicatePaymentCount: 0`, `redisStock: 40` |
| distributed | 3 | 24/24 | `expected_race_loser: 10`, `unexpected: 0`, `payment_success: 2`, `expire_success: 8` | `confirmed: 2`, `expired: 8`, `payments: 2`, `duplicatePaymentCount: 0`, `redisStock: 40` |

Interpretation:

- Every run classified race-loser responses as expected and kept
  `unexpected_race_response` at 0.
- Payment and expiration winners vary naturally by race timing, so the evidence
  is interpreted through threshold counters and final domain summaries.
- Per-reservation state transition invariants remain covered by
  `ReservationStateTransitionRaceIntegrationTest`.

## Scenario E: Duplicate Request / Idempotency

| Strategy | Runs | Checks | Key counters | Final domain summary |
| --- | ---: | ---: | --- | --- |
| pessimistic | 3 | 78/78 | each run: `reservation replay: 20`, `payment replay: 20`, `conflict: 1`, `request_fail: 0` | each run: `reservationCount: 1`, `paymentCount: 1`, duplicate rows `0` |
| optimistic | 3 | 78/78 | each run: `reservation replay: 20`, `payment replay: 20`, `conflict: 1`, `request_fail: 0` | each run: `reservationCount: 1`, `paymentCount: 1`, duplicate rows `0` |
| distributed | 3 | 78/78 | each run: `reservation replay: 20`, `payment replay: 20`, `conflict: 1`, `request_fail: 0` | each run: `reservationCount: 1`, `paymentCount: 1`, duplicate rows `0` |

Interpretation:

- Same-key replay branches were absorbed without duplicate reservation or payment
  rows.
- Same-key different-seat reservation produced the intended conflict branch.
- This evidence does not claim byte-identical replay response bodies.

## Scenario F: Queue Token Abuse

| Strategy | Runs | Checks | Key counters | Final domain summary |
| --- | ---: | ---: | --- | --- |
| pessimistic | 3 | 48/48 | each run: `unauth success: 0`, `unauth reject: 4`, `unexpected reject: 0`, `normal success: 1` | each run: `reservationCount: 1`, `paymentCount: 0`, `redisStock: 50` |
| optimistic | 3 | 48/48 | each run: `unauth success: 0`, `unauth reject: 4`, `unexpected reject: 0`, `normal success: 1` | each run: `reservationCount: 1`, `paymentCount: 0`, `redisStock: 50` |
| distributed | 3 | 48/48 | each run: `unauth success: 0`, `unauth reject: 4`, `unexpected reject: 0`, `normal success: 1` | each run: `reservationCount: 1`, `paymentCount: 0`, `redisStock: 49` |

Interpretation:

- Missing token, other user token, other schedule token, and expired token
  branches were rejected in all repeats.
- The normal token path succeeded once in every run.
- This is queue-token abuse branch and threshold evidence, not capacity evidence.

## Remaining Limits

- The run was local and single-machine.
- It does not provide production latency, throughput, error-rate, SLO, or
  capacity claims.
- Raw artifacts are generated under `k6/results/` and are not committed.
