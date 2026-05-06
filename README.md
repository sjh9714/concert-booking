# Concert Booking

Concert Booking은 콘서트 좌석 예매를 주제로 동시성 제어 전략을 비교하는 Spring Boot 백엔드입니다. 비관적 락, 낙관적 락, Redis 분산 락을 구현하고, 대기열, 좌석 임시 점유, 결제, Kafka 이벤트, 만료 스케줄러까지 예매 흐름에 필요한 요소를 함께 구성했습니다.

## 문제 의식

좌석 예매는 한정된 좌석에 요청이 몰리는 대표적인 동시성 문제입니다. 이 저장소는 overselling을 막기 위한 락 전략을 분리하고, Redis 기반 대기열과 Kafka 기반 좌석 반환 이벤트를 결합해 실제 예매 흐름에 가까운 백엔드 구조를 실험합니다.

## 주요 기능

- 회원가입, 로그인, JWT 인증
- 콘서트와 공연 일정 조회
- 좌석 현황 조회
- Redis Sorted Set 기반 대기열 진입
- SSE 기반 실시간 대기 순번 스트림
- 입장 토큰 기반 예매 API 접근 제어
- 좌석 예매, 예매 상세 조회, 결제 확정
- 비관적 락, 낙관적 락, Redis 분산 락 전략 전환
- Redis TTL 기반 좌석 임시 점유
- Kafka 예매 완료/취소 이벤트와 좌석 반환 consumer
- 만료 예매 스케줄러와 ShedLock
- Testcontainers 통합 테스트와 k6 부하 테스트

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot 3.4.1, Spring Web, Spring Security |
| Persistence | Spring Data JPA, PostgreSQL 16 |
| Lock / Queue | Redis 7, Redisson, ShedLock |
| Messaging | Kafka, Spring Kafka |
| Realtime | Server-Sent Events |
| Test / Perf | JUnit 5, Testcontainers, k6 |
| Build / Infra | Gradle Kotlin DSL, Docker Compose |

## 예매 흐름

```text
대기열 진입
→ 입장 가능 순번이면 토큰 발급
→ 좌석 예매 요청
→ 선택된 reservation.strategy로 좌석 정합성 제어
→ 결제 요청
→ reservation.completed 이벤트 발행
→ 취소/만료 시 reservation.cancelled 이벤트로 좌석 반환
```

## 구조

```text
src/main/java/com/concert/booking/
├── common/       # JWT, 예외, interceptor, util
├── config/       # Redis, Kafka, Security, scheduler, strategy
├── consumer/     # 좌석 반환 Kafka consumer
├── controller/   # Auth, Concert, Queue, Reservation, Payment, Admin API
├── domain/       # Concert, Seat, Reservation, Payment 등
├── dto/          # 요청/응답 DTO
├── event/        # ReservationCompleted/Cancelled event
├── repository/   # JPA Repository
└── service/      # 예매, 결제, 대기열, 인증, 콘서트 서비스
```

## 실행 방법

PostgreSQL, Redis, Kafka, Kafka UI를 Docker Compose로 실행합니다.

```bash
docker compose up -d
```

애플리케이션을 실행합니다.

```bash
./gradlew bootRun
```

테스트는 Testcontainers를 사용합니다.

```bash
./gradlew test
```

락 전략은 `src/main/resources/application.yml`의 `reservation.strategy` 값으로 전환합니다.

```yaml
reservation:
  strategy: pessimistic # pessimistic | optimistic | distributed
```

## API 요약

| Method | Path | 설명 |
| --- | --- | --- |
| POST | `/api/auth/signup` | 회원가입 |
| POST | `/api/auth/login` | 로그인 |
| GET | `/api/concerts` | 콘서트 목록 |
| GET | `/api/concerts/{id}/schedules/{scheduleId}/seats` | 좌석 현황 |
| POST | `/api/queue/enter` | 대기열 진입 |
| GET | `/api/queue/events` | SSE 대기 순번 스트림 |
| POST | `/api/reservations` | 좌석 예매 |
| POST | `/api/payments` | 결제 요청 |

## 성능 테스트

`k6/`에는 락 전략 비교를 위한 시나리오가 포함되어 있습니다.

### 정합성·전략 비교 포인트

- 비관적 락, 낙관적 락, Redis 분산 락 세 가지 전략을 같은 좌석 예매 시나리오에서 비교했습니다.
- k6 부하 테스트로 전략별 RPS, p95 latency, 실패율을 측정하고 `overselling 0건`을 정합성 기준으로 확인했습니다.
- Redis 대기열, 좌석 임시 점유 TTL, Kafka 좌석 반환 이벤트, ShedLock 스케줄러를 함께 두어 단일 예매 API를 넘어 실제 예매 흐름에 가까운 구조를 검증했습니다.

```bash
k6 run k6/scenario-a.js
k6 run k6/scenario-b.js
k6 run k6/scenario-c.js
```

상세 결과는 `docs/PERF_RESULT.md`에서 확인할 수 있습니다.

## 문서

- `docs/DESIGN.md`: ERD, API, 전략, 대기열, Kafka, ADR
- `docs/STUDY_GUIDE.md`: 코드 흐름 학습 가이드
- `docs/PERF_RESULT.md`: k6 기반 성능 측정 결과
