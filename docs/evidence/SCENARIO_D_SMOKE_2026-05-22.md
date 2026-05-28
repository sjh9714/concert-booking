# 결제/만료 race 검증 (Scenario D) Smoke Evidence

## Scope

This is a small local smoke record for the payment / expiration race path.
It is not a formal load-test result and must not be used as throughput,
latency, error-rate, or production performance evidence; claim boundary: local smoke evidence only.

The latest run uses the refined `k6/scenario-d.js` assertion model:

- expected race-loser responses are counted as `expected_race_loser`
- unexpected responses are counted as `unexpected_race_response`
- `unexpected_race_response == 0` is enforced as a k6 threshold

Formal three-scenario local repeat evidence is summarized separately in
`SCENARIO_D_E_F_FORMAL_2026-05-22.md`. This smoke remains a small branch record.

## Latest Refined Smoke Command

```bash
RESULTS_ROOT="$PWD/k6/results/20260522-153959-scenario-d-refined-threshold-smoke" \
SMOKE=1 \
STRATEGY=pessimistic \
SCENARIO=scenario-d \
VUS=20 \
RUNS=1 \
USER_COUNT=20 \
SCHEDULE_ID=1 \
bash k6/run-all.sh
```

## Latest Refined Raw Artifacts

```txt
k6/results/20260522-153959-scenario-d-refined-threshold-smoke/pessimistic/scenario-d/run-1/
├── events.json
├── final-summary.json
├── k6.log
├── reset.json
└── summary.json
```

## Latest Refined Observed Summary

| Item | Observed |
| --- | ---: |
| checks | 24/24 passed |
| expected_race_loser | 10 |
| unexpected_race_response | 0 |
| expire_success | 10 |
| payment_success | 0 |
| http_req_failed | 10 / 63 |
| reservationCount | 10 |
| confirmedReservationCount | 0 |
| expiredReservationCount | 10 |
| paymentCount | 0 |
| duplicatePaymentCount | 0 |

## Latest Refined Interpretation

- The smoke created 10 pending reservations, then raced payment and expiration
  calls across 20 VUs.
- In this run, expiration won all 10 races and payment attempts became expected
  race-loser responses.
- `unexpected_race_response: 0` confirms the refined assertion model did not
  observe an unclassified response in this local smoke run.
- This is still not a formal load-test result. It only confirms the race path
  and assertion split on one small local run.

## Archived Pre-refinement Raw Artifacts

```txt
k6/results/20260522-152329-scenario-d-smoke/pessimistic/scenario-d/run-1/
├── events.json
├── final-summary.json
├── k6.log
├── reset.json
└── summary.json
```

## Archived Pre-refinement Observed Summary

| Item | Observed |
| --- | ---: |
| checks | 24/24 passed |
| payment_success | 1 |
| expire_success | 9 |
| invalid_state_count | 10 |
| http_req_failed | 9 / 63 |
| reservationCount | 10 |
| confirmedReservationCount | 1 |
| expiredReservationCount | 9 |
| paymentCount | 1 |
| duplicatePaymentCount | 0 |

## Archived Pre-refinement Interpretation

- The smoke created 10 pending reservations, then raced payment and expiration
  calls across 20 VUs.
- The final domain summary had one confirmed reservation/payment and nine
  expired reservations.
- `duplicatePaymentCount: 0` is useful as a sanity check, but the script does
  not distinguish expected race-loser responses from true invalid states in
  this archived run.
- Keep the archived run as debugging context only.
