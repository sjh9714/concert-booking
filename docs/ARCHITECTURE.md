# Architecture

Concert Booking의 전체 구조는 대기열 입장 제어, 예매 트랜잭션, 결제/만료 상태 전이, Outbox 기반 비동기 이벤트, 실패 재처리 경계를 분리합니다.

![Concert Booking 전체 아키텍처](assets/architecture/overall-architecture.svg)

이 다이어그램은 구현된 핵심 흐름과 검증 대상 경계를 설명하기 위한 단순화된 구조도이며, 운영 배포 토폴로지나 production SLO를 주장하지 않습니다.

## 핵심 흐름

| 흐름 | 기준 데이터 | 설명 |
| --- | --- | --- |
| 입장 제어 | Redis Queue / Token | 사용자는 Queue API를 통해 대기열에 진입하고, `userId + scheduleId`에 바인딩된 입장 토큰을 받은 뒤 예매 API로 진입합니다. |
| 예매 트랜잭션 | PostgreSQL | Reservation API는 Queue Token을 확인하고 Reservation Tx 안에서 좌석 HOLD, 예약 생성, Outbox Table 기록을 처리합니다. 최종 정합성 기준은 DB입니다. |
| 결제/만료 | PostgreSQL | Payment API와 Expiration Scheduler는 reservation row lock과 상태 전이 규칙으로 `PENDING -> CONFIRMED/CANCELLED/EXPIRED` 경계를 보호합니다. |
| 비동기 이벤트 | Outbox Table / Kafka Topic | 비즈니스 트랜잭션은 Outbox Table에 이벤트 발행 의도를 남기고, Outbox Relay가 Kafka Topic으로 발행합니다. |
| 실패 처리 | DLT / Admin Replay API / Reconciliation Job | Consumer 실패는 DLT로 격리하고, 제한된 관리자 replay와 Redis stock reconciliation으로 수동 검산/보정 경로를 둡니다. |

## 설계 판단

| 판단 | 이유 | 현재 검증 경계 |
| --- | --- | --- |
| DB를 좌석 정합성의 최종 기준으로 둠 | Redis stock은 빠른 선검증용 캐시라서 불일치 가능성을 별도로 다룹니다. | Testcontainers와 k6 로컬 시나리오 검증 |
| Queue Token을 Reservation Tx 앞단에서 검증 | 대기열 우회와 다른 사용자/다른 공연 일정 token 재사용을 차단합니다. | `QueueTokenPolicyIntegrationTest`, Scenario F 시나리오 검증 |
| Outbox로 Kafka publish 경계를 분리 | DB commit 이후 이벤트 발행 실패가 조용히 사라지는 구간을 줄입니다. | `OutboxIntegrationTest`, `KafkaDltReplayIntegrationTest` |
| DLT replay와 reconciliation을 수동 utility로 제한 | 자동 복구 시스템이나 운영 SLO를 주장하지 않고, 포트폴리오 검증용 실패 대응 경계를 명확히 둡니다. | `ROLE_ADMIN` 보호와 local 검증 |

## 주요 컴포넌트

| 컴포넌트 | 역할 |
| --- | --- |
| Client | Queue, Reservation, Payment, Admin API 호출자입니다. |
| Queue API | 대기열 진입, 순번 조회, 입장 토큰 발급을 담당합니다. |
| Reservation API | 입장 토큰과 idempotency key를 확인하고 예매 생성을 시작합니다. |
| Payment API | 결제 idempotency와 reservation 상태 전이를 처리합니다. |
| Expiration Scheduler | 만료된 PENDING reservation을 별도 트랜잭션으로 만료 처리합니다. |
| Reservation Tx | 좌석 HOLD, 예약 생성, 상태 전이, Outbox 기록의 트랜잭션 경계입니다. |
| PostgreSQL | 좌석/예약/결제/Outbox의 최종 기준 데이터 저장소입니다. |
| Redis Queue / Token / Stock | 대기열, 입장 토큰, 재고 선검증 캐시를 담당합니다. |
| Outbox Relay | publish 가능한 Outbox event를 Kafka로 발행하고 실패 시 retry/dead 상태를 기록합니다. |
| Kafka Topic | reservation event를 consumer로 전달합니다. |
| Consumer | 취소/만료 이벤트를 받아 좌석 반환을 멱등 처리합니다. |
| DLT | consumer 처리 실패 메시지를 격리합니다. |
| Admin Replay API | DLT 메시지를 관리자 권한으로 제한적으로 재처리합니다. |
| Reconciliation Job | DB 기준 좌석 수와 Redis stock 불일치를 검산하고 필요 시 수동 보정합니다. |
