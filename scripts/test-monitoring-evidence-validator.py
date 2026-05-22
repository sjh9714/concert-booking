#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "scripts" / "validate-monitoring-evidence.py"


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8")


def base_capture(directory: Path) -> None:
    write_json(
        directory / "targets.json",
        {
            "status": "success",
            "data": {
                "activeTargets": [
                    {
                        "health": "up",
                        "labels": {"job": "concert-booking-local"},
                        "scrapeUrl": "http://host.docker.internal:8080/actuator/prometheus",
                    }
                ]
            },
        },
    )
    write_json(
        directory / "rules.json",
        {
            "status": "success",
            "data": {
                "groups": [
                    {
                        "rules": [
                            {"name": "OutboxDeadEventsPresent"},
                            {"name": "StockMismatchDetected"},
                            {"name": "QueueTokenValidationFailuresHigh"},
                        ]
                    }
                ]
            },
        },
    )
    write_json(
        directory / "up-query.json",
        {"status": "success", "data": {"result": [{"value": [0, "1"]}]}},
    )
    write_json(
        directory / "outbox-events-query.json",
        {"status": "success", "data": {"result": [{"value": [0, "0"]}]}},
    )
    write_json(
        directory / "queue-token-failures-query.json",
        {"status": "success", "data": {"result": [{"value": [0, "0"]}]}},
    )


def run_validator(directory: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(VALIDATOR), str(directory)],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def main() -> int:
    with tempfile.TemporaryDirectory() as temp:
        valid_dir = Path(temp) / "valid"
        valid_dir.mkdir()
        base_capture(valid_dir)

        result = run_validator(valid_dir)
        if result.returncode != 0:
            print(result.stdout)
            print(result.stderr, file=sys.stderr)
            raise AssertionError("validator rejected a valid fixture")

        summary = json.loads((valid_dir / "capture-summary.json").read_text())
        assert summary["claimStatus"] == (
            "local-prometheus-scrape-not-production-observability"
        )
        assert summary["healthyTargetCount"] == 1

        missing_alert_dir = Path(temp) / "missing-alert"
        missing_alert_dir.mkdir()
        base_capture(missing_alert_dir)
        write_json(
            missing_alert_dir / "rules.json",
            {
                "status": "success",
                "data": {
                    "groups": [
                        {"rules": [{"name": "OutboxDeadEventsPresent"}]}
                    ]
                },
            },
        )
        result = run_validator(missing_alert_dir)
        assert result.returncode != 0
        assert "missing alert rules" in result.stderr

        down_target_dir = Path(temp) / "down-target"
        down_target_dir.mkdir()
        base_capture(down_target_dir)
        write_json(
            down_target_dir / "targets.json",
            {
                "status": "success",
                "data": {
                    "activeTargets": [
                        {
                            "health": "down",
                            "labels": {"job": "concert-booking-local"},
                        }
                    ]
                },
            },
        )
        result = run_validator(down_target_dir)
        assert result.returncode != 0
        assert "no healthy concert-booking-local" in result.stderr

    print("monitoring evidence validator smoke test passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
