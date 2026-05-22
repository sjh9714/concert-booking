#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path


REQUIRED_ALERTS = {
    "OutboxDeadEventsPresent",
    "StockMismatchDetected",
    "QueueTokenValidationFailuresHigh",
}

REQUIRED_FILES = {
    "targets": "targets.json",
    "rules": "rules.json",
    "up_query": "up-query.json",
    "outbox_query": "outbox-events-query.json",
    "queue_token_query": "queue-token-failures-query.json",
}


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise AssertionError(f"missing required evidence file: {path.name}") from exc
    except json.JSONDecodeError as exc:
        raise AssertionError(f"invalid JSON in {path.name}: {exc}") from exc


def require_success(name: str, payload: dict) -> None:
    if payload.get("status") != "success":
        raise AssertionError(f"{name} status must be success, got {payload.get('status')!r}")


def active_concert_targets(targets: dict) -> list[dict]:
    active_targets = targets.get("data", {}).get("activeTargets", [])
    return [
        target
        for target in active_targets
        if target.get("labels", {}).get("job") == "concert-booking-local"
    ]


def alert_names(rules: dict) -> set[str]:
    names: set[str] = set()
    for group in rules.get("data", {}).get("groups", []):
        for rule in group.get("rules", []):
            name = rule.get("name")
            if name:
                names.add(name)
    return names


def query_result_count(payload: dict) -> int:
    result = payload.get("data", {}).get("result", [])
    return len(result)


def validate_capture(evidence_dir: Path) -> dict:
    loaded = {
        name: load_json(evidence_dir / filename)
        for name, filename in REQUIRED_FILES.items()
    }

    for name, payload in loaded.items():
        require_success(name, payload)

    concert_targets = active_concert_targets(loaded["targets"])
    healthy_targets = [
        target for target in concert_targets if target.get("health") == "up"
    ]
    if not healthy_targets:
        raise AssertionError("no healthy concert-booking-local Prometheus target found")

    names = alert_names(loaded["rules"])
    missing_alerts = sorted(REQUIRED_ALERTS - names)
    if missing_alerts:
        raise AssertionError(f"missing alert rules: {', '.join(missing_alerts)}")

    up_result_count = query_result_count(loaded["up_query"])
    if up_result_count == 0:
        raise AssertionError("up{job=\"concert-booking-local\"} returned no series")

    outbox_result_count = query_result_count(loaded["outbox_query"])
    if outbox_result_count == 0:
        raise AssertionError("concert_booking_outbox_events returned no series")

    summary = {
        "claimStatus": "local-prometheus-scrape-not-production-observability",
        "targetHealth": "up",
        "healthyTargetCount": len(healthy_targets),
        "requiredAlerts": sorted(REQUIRED_ALERTS),
        "upResultCount": up_result_count,
        "outboxResultCount": outbox_result_count,
        "queueTokenFailureResultCount": query_result_count(
            loaded["queue_token_query"]
        ),
        "interpretation": (
            "Local Prometheus server scrape evidence. This does not claim "
            "production alert firing, Grafana operation, tracing, or SLO readiness."
        ),
    }

    (evidence_dir / "capture-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return summary


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] in {"-h", "--help"}:
        print("Usage: python3 scripts/validate-monitoring-evidence.py <evidence-dir>")
        return 0 if len(sys.argv) == 2 else 2

    evidence_dir = Path(sys.argv[1])
    try:
        summary = validate_capture(evidence_dir)
    except AssertionError as exc:
        print(f"Invalid monitoring evidence: {exc}", file=sys.stderr)
        return 1

    print(
        "Valid monitoring evidence: "
        f"{evidence_dir} ({summary['healthyTargetCount']} healthy target)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
