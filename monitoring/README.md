# Monitoring Templates

이 폴더는 로컬 검증용 Prometheus/Grafana 템플릿입니다. 실제 Prometheus server scrape,
alert firing, dashboard 운영, tracing, SLO 체계를 구현했다는 주장은 하지 않습니다.

metric 이름은 README의 Micrometer 이름을 Prometheus 형식으로 매핑했습니다. `Counter`는
Prometheus scrape에서 `_total` suffix가 붙고, Outbox 상태 count는
`concert_booking_outbox_events{status="pending|failed|dead"}` gauge로 조회합니다. 실제
`PrometheusScrapeContractIntegrationTest`는 보호된 `/actuator/prometheus` 응답에 alert rule과
dashboard가 참조하는 metric name이 노출되는지 확인합니다.

현재 Spring Security 설정에서는 `/actuator/health`, `/actuator/info`만 공개하고
`/actuator/prometheus`는 보호된 actuator endpoint로 취급합니다. 이 템플릿을 실제로
scrape하려면 bearer token, 내부망 접근, 또는 로컬 검증 전용 profile처럼 명시적인 접근
정책을 먼저 구성해야 합니다.

현재 파일 그대로 외부 Prometheus server scrape가 성공한다고 주장하지 않습니다. 접근 정책을 붙인 뒤
실제 Prometheus target scrape, alert firing, dashboard render artifact를 별도로 보존해야 합니다.

현재 Timer는 histogram publish 설정을 전제로 하지 않으므로 dashboard는 p95가 아니라
`concert_booking_reservation_latency_seconds_max`를 보여줍니다. p95 패널은 histogram bucket
설정을 추가하고 `/actuator/prometheus`에서 `_bucket` series를 확인한 뒤에만 추가합니다.

## Evidence Matrix

| 항목 | 근거 | 상태 |
| --- | --- | --- |
| Actuator endpoint access policy | `ActuatorSecurityIntegrationTest` | 시나리오 검증 |
| Metric registration/classification | `BookingMetricsTest` | 시나리오 검증 |
| Outbox gauge scheduled refresh | `OutboxMetricsSnapshotSchedulingIntegrationTest` | 시나리오 검증 |
| Prometheus actuator metric contract | `PrometheusScrapeContractIntegrationTest` | 시나리오 검증 |
| Prometheus/Grafana template syntax | CI `Check monitoring template syntax` | 시나리오 검증 |
| Alert rule expression unit test | `promtool test rules monitoring/alert-rules.test.yml` | 시나리오 검증 |
| Local Prometheus server scrape artifact | `docs/evidence/monitoring/prometheus-20260522T155512Z/capture-summary.json` | 시나리오 검증 |
| Local Prometheus evidence capture format | `scripts/capture-monitoring-evidence.sh`, `scripts/validate-monitoring-evidence.py`, `scripts/test-monitoring-evidence-validator.py` | 시나리오 검증 |
| Local monitoring harness | `SPRING_PROFILES_ACTIVE=local-monitoring`, `docker-compose.monitoring.yml`, `scripts/monitoring-local-verify.sh` | 시나리오 검증 |
| Alert firing / dashboard operation / tracing / SLO | local scrape artifact는 있지만 운영형 증거 없음 | 추가 측정 예정 |

이 표의 actuator contract test는 Spring Boot actuator 응답과 템플릿 metric name의 계약만 확인합니다.
template syntax 검사는 Prometheus/Grafana 파일이 파싱 가능한지만 확인하고, alert rule unit test는
synthetic time series에서 rule expression과 annotation이 맞는지만 확인합니다. 셋 모두 실제 Prometheus
server scrape, alert firing, dashboard render, tracing, SLO 운영 증거로 해석하지 않습니다.

## Local Prometheus Evidence Capture

실제 local Prometheus server와 앱을 띄운 뒤에는 아래 명령으로 target/rule/query artifact를 남깁니다.

먼저 앱은 로컬 전용 monitoring admin bootstrap profile로 실행합니다.

```bash
SPRING_PROFILES_ACTIVE=local-monitoring ./gradlew bootRun
```

다른 shell에서 local Prometheus/Grafana harness와 capture를 실행합니다.

```bash
bash scripts/monitoring-local-verify.sh
```

현재 보존된 `docs/evidence/monitoring/prometheus-20260522T155512Z/capture-summary.json`은 local Prometheus
target 1개가 `up`이고, 필수 alert rule이 로딩됐으며, Outbox query series가 존재함을 검산합니다.
이 artifact는 production alert firing, Grafana dashboard 운영, tracing, SLO readiness 증거가 아닙니다.

`monitoring/prometheus.local.yml`은 bearer token file을 사용합니다. token file은
`monitoring/.generated/concert-booking.token`에 생성되며 git에 포함하지 않습니다.
