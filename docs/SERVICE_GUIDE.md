# 예매 코드를 읽는 순서

## 상태와 기준 데이터

예약은 `PENDING → CONFIRMED / CANCELLED / EXPIRED`, 좌석은 `AVAILABLE → HELD → RESERVED` 또는 `HELD → AVAILABLE`입니다. 확정 뒤 취소·환불은 이번 범위에 없습니다. 선점은 5분이며 만료 작업은 30초마다 실행하므로 선점 시간이 지나도 반환까지 최대 한 주기의 지연이 있습니다. 결제는 서버 시간이 만료를 넘으면 거절합니다.

좌석의 `current_reservation_id`는 지금 좌석을 가진 예약입니다. `reservation_seats`는 과거 예약에도 남는 관계이므로 현재 소유권으로 쓸 수 없습니다. 목록의 잔여석은 좌석 상태를 세어 계산합니다. 기존 `concert_schedules.available_seats`는 실험 모드의 과거 호환 필드이며 기본 서비스의 기준 값이 아닙니다.

## 한 요청 따라가기

1. `controller/ReservationController`: 인증된 사용자·DTO·중복 요청 키를 받습니다.
2. `service/reservation/SeatLockReservationService`: `service` 프로필의 기본 진입점입니다. 기존 조회·취소 코드는 재사용합니다.
3. `ReservationOrchestrator`와 `ReservationIdempotencyService`: 사용자·키·요청 내용의 관계를 확인하고 기존 성공 응답을 재사용합니다. 키를 다른 내용으로 재사용하면 충돌입니다.
4. `ReservationCreationService`: 업무 트랜잭션 안에서 해당 일정의 선택 좌석을 ID 순서로 잠급니다. 예약 ID를 만든 뒤 좌석 소유권과 예약 좌석 관계를 함께 저장합니다.
5. `PaymentService`: 예약 행을 잠근 뒤 본인·상태·만료·기존 결제를 확인합니다. 해당 예약이 가진 좌석만 확정하고 테스트 결제 기록을 같은 트랜잭션에 저장합니다.
6. `ReservationCancellationService`, `ReservationExpirationScheduler` → `SeatReleaseService`: 예약 행, 좌석 행 순으로 잠급니다. 종료 상태와 소유 예약 ID가 모두 맞을 때만 좌석을 반환합니다. 같은 DB의 변경이므로 기본 경로에 Kafka 이벤트를 두지 않습니다.

실험 모드의 카운터·Redis·outbox 코드는 남아 있지만 `application-service.yml`에서 기본 시연과 분리했습니다. 기본 프로필은 `service`, Compose는 `service,demo`입니다. 기존 실험 Compose의 `demo`/`e2e`는 기존 전략 설정과 대기열을 사용합니다. 이미 사용 중인 DB에서 두 모드를 번갈아 운영하는 마이그레이션은 검증하지 않았으므로 전용 데모 볼륨을 사용합니다.

## 선택과 한계

같은 좌석 충돌은 DB가 직렬화해야 합니다. 서로 다른 좌석까지 한 일정 카운터에서 대기하지 않도록 공유 갱신을 제거했습니다. 남은 좌석을 매번 세는 읽기 비용은 증가할 수 있으며, 대규모 공연 목록의 조회 비용은 별도 측정 대상입니다. Redis·분산 락·대기열이 모든 상황에서 불필요하다는 결론은 아닙니다.

8개 요청의 동시성 검증은 정합성 사례입니다. 상용 티켓 오픈 부하, 공정한 대기 순서, 서버 장애 복구 시간, 실제 PG 결제의 보장은 검증하지 않았습니다. 기존 대기열·세 락 전략 실험은 별도 자료로 읽습니다.

## 구현 후 공부

김영한 Spring MVC의 DTO·검증 → Spring DB의 트랜잭션·잠금 → JPA의 변경 감지·연관관계를 위 요청 순서에 대응시킵니다. 딩코딩코 4주차의 동시성 개념은 `ServiceBookingIntegrationTest`와 연결합니다. 아래는 강의 원문 과제가 아닌 이번 코드용 보충 과제입니다.

- 선점 시간을 5분에서 3분으로 바꿉니다. 서버의 `expiresAt`, 결제 검증, 만료 검사, 화면의 카운트다운이 같은 기준인지 설명하고 테스트를 바꿉니다.
- A 취소 → B 재선점 → A 반환 재처리에서 소유권 검사를 지우면 어느 테스트가 왜 실패하는지 예측합니다.
- 두 좌석 중 하나가 이미 선점된 경우 다른 좌석도 남겨 두어야 하는 이유와 트랜잭션 경계를 설명합니다.
- 같은 키를 재사용한 결제와 다른 키로 같은 예약을 결제한 요청의 응답이 왜 다른지 코드에서 찾습니다.
