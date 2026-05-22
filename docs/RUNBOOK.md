# Concert Booking Runbook

이 문서는 포트폴리오 설명과 로컬 검증을 위한 운영 절차 초안입니다. 실제 운영 환경의
자동 복구, 온콜, SLO, tracing 체계를 구현했다는 주장은 하지 않습니다.

## 1. Outbox DEAD 증가

### 증상

- `concert_booking_outbox_events{status="dead"}` 값이 0보다 큽니다.
- outbox event가 retry를 초과해 manual replay 후보가 됩니다.
- Kafka publish 실패와 consumer 실패를 먼저 구분해야 합니다.

### 확인

1. `/actuator/prometheus`에서 outbox 관련 metric을 확인합니다.
2. `outbox_events`의 `status`, `event_type`, `aggregate_id`, `retry_count`,
   `last_error`를 확인합니다.
3. Kafka broker 상태와 대상 topic 접근 가능 여부를 확인합니다.
4. consumer 처리 실패라면 DLT topic lag와 payload를 확인합니다.

상태별 판단 기준:

| 상태 | 의미 | 우선 확인 | 조치 |
| --- | --- | --- | --- |
| `PENDING` | 발행 대기 | `nextAttemptAt`, relay scheduler 실행 여부 | 자동 relay 대상인지 확인 |
| `PUBLISHED` | Kafka publish 성공 | consumer log, DLT topic | consumer 처리 결과 확인 |
| `FAILED` | publish 실패 후 재시도 대기 | `retry_count`, `last_error`, broker 상태 | 자동 재시도 또는 broker 복구 |
| `DEAD` | 재시도 초과 | payload, aggregate 최신 상태, idempotency 기준 | 제한된 manual replay 또는 보류 |

### 조치

1. 같은 이벤트가 이미 처리됐는지 consumer idempotency 기준을 먼저 확인합니다.
2. payload가 안전하게 재처리 가능한 이벤트인지 확인합니다.
3. Kafka publish 실패라면 relay 재시도 가능 상태로 되돌릴지 판단합니다.
4. consumer 실패라면 DLT replay endpoint를 제한된 건수로 실행합니다.
5. replay 후 outbox status, consumer log, 관련 metric을 다시 확인합니다.

### 주의

- Outbox는 exactly-once를 보장하지 않습니다.
- 중복 처리는 consumer idempotency와 도메인 상태 전이 조건으로 흡수해야 합니다.
- DEAD event를 일괄 재처리하기 전에 같은 aggregate의 최신 상태를 확인합니다.

## 2. DLT Replay

### 확인할 것

- DLT topic 이름은 Spring Kafka `DeadLetterPublishingRecoverer` 규칙을 따릅니다.
  예: `reservation.cancelled.DLT`.
- replay 대상 이벤트의 aggregate 상태가 replay 가능한지 확인합니다.
- 관리자 권한 경로이므로 `ROLE_ADMIN`이 필요합니다.

### 절차

```http
POST /api/admin/dlt/replay?topic=reservation.cancelled.DLT&limit=10
```

1. 처음에는 작은 `limit`으로 replay합니다.
2. replay 결과와 consumer idempotency log를 확인합니다.
3. 같은 이벤트를 다시 replay해도 좌석 반환이 중복 증가하지 않는지 확인합니다.

## 3. Redis Stock Mismatch

Redis stock은 빠른 조회와 DB 진입 전 차단을 위한 보조 상태입니다. 최종 기준 데이터는
DB의 `Seat.status = AVAILABLE` count입니다.

### 확인

```http
POST /api/admin/schedules/{scheduleId}/stock/reconcile?repair=false
```

- DB 기준 available seat count와 Redis stock 값을 비교합니다.
- mismatch가 있으면 분산 락 실패, consumer 실패, 수동 fixture reset 여부를 확인합니다.

### 보정

```http
POST /api/admin/schedules/{scheduleId}/stock/reconcile?repair=true
```

보정 후에는 같은 endpoint를 `repair=false`로 다시 호출해 mismatch가 해소됐는지 확인합니다.

### 주의

- Redis 값을 최종 기준으로 DB를 수정하지 않습니다.
- 좌석 상태는 DB transaction과 도메인 상태 전이 결과를 기준으로 판단합니다.

## 4. Queue Token Abuse

### 의심 상황

- `concert_booking_queue_token_validation_failures_total` 증가량이 평소보다 큽니다.
- reservation API에 token 없이 접근하거나 다른 사용자/다른 schedule token을 재사용합니다.
- 만료 token 또는 이미 처리 중인 token으로 반복 요청합니다.

### 확인

1. 실패 사유별 log와 metric을 확인합니다.
2. Redis key pattern을 확인합니다.
   - `token:queue:{userId}:{scheduleId}`
   - `token:queue:{userId}:{scheduleId}:inflight`
3. 같은 userId가 여러 schedule token을 혼용했는지 확인합니다.

### 조치

- 정상 사용자 오류라면 재입장 후 token 재발급을 안내합니다.
- abuse가 의심되면 userId/IP 기준 rate limit 또는 차단 정책을 별도 운영 계층에서 적용합니다.
- k6 `scenario-f`는 unauthorized success가 0이어야 하는 abuse smoke 검증입니다. 정식 부하
  수치로 사용하지 않습니다.

## 5. 장애 후 확인 체크리스트

| 항목 | 확인 기준 |
| --- | --- |
| Outbox | `PENDING`, `FAILED`, `DEAD` count가 예상 범위인지 확인 |
| DLT | replay 후 같은 message가 중복 처리되지 않는지 확인 |
| Redis stock | DB 기준 reconciliation 결과 mismatch가 0인지 확인 |
| Reservation | success/fail count와 overselling 여부 확인 |
| Queue token | validation failure가 특정 user/schedule에 집중되는지 확인 |
