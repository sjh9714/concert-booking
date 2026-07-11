# Concert Booking Architecture

이 문서는 전체 기술 스택을 한 장에 나열하지 않습니다. 실제 예약 흐름에서 서로 다른 두 질문만 분리해
설명합니다.

1. 같은 좌석을 놓친 사용자는 어떻게 예매를 계속하는가?
2. commit 뒤 좌석 반환 이벤트가 실패하면 어떻게 다시 처리하는가?

## 1. 좌석 경쟁과 사용자 복구

| 단계 | 사용자·클라이언트 | 애플리케이션 경계 | 기준 데이터 |
| --- | --- | --- | --- |
| 1. 대기 | 사용자가 공연 일정의 대기열에 진입 | Redis Sorted Set에서 순번과 상태 계산 | Redis Queue |
| 2. 입장 | <code>READY</code> 뒤 토큰 요청 | Lua로 기존 토큰 확인·순위 검증·저장·queue 제거 | Redis Token |
| 3. 좌석 선택 | 최신 좌석표에서 좌석 선택 | schedule과 seat 소속을 검증 | PostgreSQL Seat |
| 4. 예약 경쟁 | Queue Token과 idempotency key로 예약 | 좌석 상태를 잠그고 한 요청만 <code>HELD</code>로 전이 | PostgreSQL |
| 5. 패자 복구 | 충돌 뒤 좌석표를 다시 조회 | 실패한 예약의 Queue Token은 소비하지 않음 | Redis Token + DB |
| 6. 결제·종료 | 데모 결제, 취소 또는 만료 | reservation row lock과 상태 전이 guard | PostgreSQL |

핵심은 "한 명만 성공"에서 끝나지 않는다는 점입니다. 경쟁에서 실패한 요청은 토큰을 잃지 않으므로 같은
사용자가 최신 좌석표에서 다른 좌석을 고를 수 있습니다.

### Queue Token 응답 유실

토큰 발급은 다음 연산을 Redis Lua 한 번으로 실행합니다.

1. 같은 사용자·공연 일정에 이미 발급된 토큰이 있는지 확인합니다.
2. 사용자가 입장 가능한 순위인지 검증합니다.
3. 만료 시각이 있는 토큰을 저장합니다.
4. 사용자를 queue에서 제거합니다.

HTTP 응답이 유실된 뒤 다시 요청하면 새 토큰을 계속 만들지 않고 기존 유효 토큰을 돌려줍니다.
토큰이 만료된 경우에는 <code>EXPIRED</code>를 표시하고 명시적으로 다시 대기열에 들어갑니다.

### Idempotency 경계

| 요청 | 같은 key + 같은 payload | 같은 key + 다른 payload | 오래 멈춘 PROCESSING |
| --- | --- | --- | --- |
| 예약 | 기존 예약 응답 | 409 conflict | stale claim 회수 후 재처리 |
| 결제 | 기존 결제 응답 | 409 conflict | stale claim 회수 후 재처리 |

DB unique constraint가 최종 중복 방어선이며, application service는 replay와 conflict를 구분합니다.

## 2. 취소·만료와 이벤트 복구

취소와 만료 transaction은 예약 상태와 Outbox event를 함께 commit합니다. 좌석 반환은 event consumer가
담당하므로, 동기 상태 전이와 비동기 전달 실패를 별도로 관찰할 수 있습니다.

| 단계 | 정상 경로 | 실패 경계 |
| --- | --- | --- |
| 1. 상태 전이 | <code>PENDING → CANCELLED/EXPIRED</code> | 결제와 동시에 실행되면 row lock 뒤 하나만 성공 |
| 2. Outbox 저장 | 같은 transaction에 반환 이벤트 저장 | transaction rollback이면 event도 없음 |
| 3. Relay | publish 성공 뒤 <code>PUBLISHED</code> | 실패하면 <code>FAILED</code>와 다음 시각 기록 |
| 4. 재시도 | backoff 뒤 다시 publish | 최대 횟수 초과 시 <code>DEAD</code>로 격리 |
| 5. Consumer | <code>HELD</code> 좌석과 재고 반환 | 처리 실패 메시지는 DLT로 이동 |
| 6. Manual replay | 원인을 확인한 이벤트만 제한적으로 재발행 | 이미 처리된 이벤트는 idempotency로 흡수 |

Outbox는 exactly-once를 만들지 않습니다. 전달은 중복될 수 있고, consumer가 같은 좌석을 두 번 반환하지
않도록 설계합니다.

## 데이터별 진실 소스

| 데이터 | 빠른 보조 상태 | 최종 판단 |
| --- | --- | --- |
| 대기 순번·입장 토큰 | Redis | Redis TTL과 token binding |
| 좌석 상태 | Redis stock pre-check | PostgreSQL <code>Seat.status</code> |
| 예약·결제 상태 | 없음 | PostgreSQL reservation/payment |
| 이벤트 발행 의도 | scheduler memory가 아님 | PostgreSQL outbox row |
| consumer 실패 | application log만이 아님 | Kafka DLT record |

Redis stock이 DB와 다르면 DB의 available seat count를 기준으로 수동 reconciliation합니다. Redis 값을
근거로 DB 좌석 상태를 덮어쓰지 않습니다.

## 검증 연결

- [두 브라우저의 좌석 경쟁·패자 복구](../web/e2e/booking.spec.ts)
- [Queue Token 정책](../src/test/java/com/concert/booking/integration/QueueTokenPolicyIntegrationTest.java)
- [예약 idempotency](../src/test/java/com/concert/booking/integration/ReservationIdempotencyIntegrationTest.java)
- [결제·취소·만료 race](../src/test/java/com/concert/booking/integration/ReservationStateTransitionRaceIntegrationTest.java)
- [Outbox relay 상태](../src/test/java/com/concert/booking/integration/OutboxIntegrationTest.java)
- [DLT replay와 좌석 반환](../src/test/java/com/concert/booking/integration/KafkaDltReplayIntegrationTest.java)

성능 측정과 운영 절차는 각각 [PERF_RESULT.md](PERF_RESULT.md), [RUNBOOK.md](RUNBOOK.md)에 분리합니다.
이 문서는 운영 topology, TPS, capacity, SLO를 주장하지 않습니다.
