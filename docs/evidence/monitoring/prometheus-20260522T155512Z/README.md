# Local Prometheus Evidence Capture

Produced by `scripts/capture-monitoring-evidence.sh`.

Captured files:

- `metadata.txt`
- `targets.json`
- `rules.json`
- `up-query.json`
- `outbox-events-query.json`
- `queue-token-failures-query.json`
- `capture-summary.json`

This artifact proves only that a local Prometheus server exposed the expected
target, rules, and key query series at capture time. It does not prove production
alert firing, Grafana dashboard operation, tracing, SLO, or on-call readiness.
