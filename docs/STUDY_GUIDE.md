# 콘서트 좌석 예매 시스템 — 완전 학습 가이드

> 이 문서 하나로 프로젝트의 모든 것을 이해할 수 있도록 작성했습니다.
> 코드를 직접 따라가며 읽으면 가장 효과적입니다.

---

## 목차

### Part 1. 프로젝트 기초
1. [프로젝트 한 줄 요약](#1-프로젝트-한-줄-요약)
2. [기술 스택과 각각의 역할](#2-기술-스택과-각각의-역할)
3. [인프라 구성 (Docker Compose)](#3-인프라-구성-docker-compose)
4. [패키지 구조 — 왜 이렇게 나눴는가](#4-패키지-구조--왜-이렇게-나눴는가)
5. [데이터베이스 설계 (7개 테이블)](#5-데이터베이스-설계-7개-테이블)

### Part 2. 도메인과 비즈니스 로직
6. [도메인 엔티티 — Rich Domain Model](#6-도메인-엔티티--rich-domain-model)
7. [좌석 상태 머신 (State Machine)](#7-좌석-상태-머신-state-machine)
8. [JWT 인증 — 요청이 처리되기까지](#8-jwt-인증--요청이-처리되기까지)
9. [예매 흐름 — 핵심 비즈니스 로직](#9-예매-흐름--핵심-비즈니스-로직)
10. [결제 흐름](#10-결제-흐름)
11. [예외 처리 설계](#11-예외-처리-설계)

### Part 3. 동시성 제어 Deep Dive
12. [동시성 문제란 무엇인가](#12-동시성-문제란-무엇인가)
13. [전략 1: 비관적 락 (Pessimistic Lock)](#13-전략-1-비관적-락-pessimistic-lock)
14. [전략 2: 낙관적 락 (Optimistic Lock)](#14-전략-2-낙관적-락-optimistic-lock)
15. [비관적 락 vs 낙관적 락 — 완전 비교](#15-비관적-락-vs-낙관적-락--완전-비교)
16. [전략 패턴으로 락 전략 교체하기](#16-전략-패턴으로-락-전략-교체하기)

### Part 4. 테스트와 검증
17. [테스트 전략과 Testcontainers](#17-테스트-전략과-testcontainers)
18. [동시성 테스트 — 왜 1명만 성공하는가](#18-동시성-테스트--왜-1명만-성공하는가)

### Part 5. Spring Boot 심화
19. [Spring Boot 핵심 개념 정리](#19-spring-boot-핵심-개념-정리)
20. [설정 파일 해설](#20-설정-파일-해설)
21. [디자인 패턴과 설계 원칙](#21-디자인-패턴과-설계-원칙)
22. [자주 묻는 질문 (FAQ)](#22-자주-묻는-질문-faq)

---

## 1. 프로젝트 한 줄 요약

**"1만 명이 동시에 1,000석을 예매할 때, 데이터 정합성을 어떻게 보장할 것인가?"**

이 프로젝트는 콘서트 좌석 예매 시스템을 만들면서 **동시성 제어**를 깊이 있게 다루는 백엔드 포트폴리오입니다.

### 전체 사용자 흐름

```
회원가입 → 로그인(JWT 발급) → 콘서트 목록 조회 → 스케줄 선택 → 좌석 조회
→ 좌석 선택(최대 4석) → 예매(좌석 HOLD, 5분 타이머) → 결제 → 예매 확정
```

### 현재 구현된 범위

| 기능 | 상태 | 구현 차수 |
|------|------|-----------|
| 회원가입/로그인 (JWT) | 완료 | 1차 |
| 콘서트/스케줄/좌석 조회 | 완료 | 1차 |
| 좌석 예매 (비관적 락) | 완료 | 1차 |
| 결제 (mock PG) | 완료 | 1차 |
| 예매 취소 | 완료 | 1차 |
| 통합 테스트 + 동시성 테스트 | 완료 | 1차 |
| 좌석 예매 (낙관적 락) | 완료 | 2차 |
| 낙관적 락 동시성 테스트 | 완료 | 2차 |

---

## 2. 기술 스택과 각각의 역할

### 핵심 스택

| 기술 | 버전 | 이 프로젝트에서의 역할 |
|------|------|----------------------|
| **Java** | 21 | 메인 언어. Virtual Thread, Record 등 최신 기능 활용 |
| **Spring Boot** | 3.4.1 | 웹 프레임워크. 자동 설정, DI, AOP, 트랜잭션 관리 |
| **Spring Data JPA** | - | ORM. Entity ↔ DB 테이블 매핑, Repository 자동 구현 |
| **Spring Security** | - | 인증/인가. JWT 필터 체인 구성 |
| **Spring Retry** | - | 재시도 로직. 낙관적 락 충돌 시 자동 재시도 |
| **PostgreSQL** | 16 | 메인 DB. 비관적 락(`SELECT FOR UPDATE`) 지원 |
| **Redis** | 7 | 캐시, 분산 락, 대기열 (3차 구현 예정) |
| **Kafka** | 3.9 | 이벤트 기반 처리 (3차 구현 예정) |

### 라이브러리

| 라이브러리 | 역할 |
|-----------|------|
| **jjwt** (0.12.6) | JWT 토큰 생성/검증 (HMAC-SHA256) |
| **Lombok** | 보일러플레이트 코드 제거 (`@Getter`, `@RequiredArgsConstructor` 등) |
| **Redisson** (3.40.2) | Redis 분산 락 클라이언트 (3차에서 활용) |
| **ShedLock** (6.2.0) | 스케줄러 중복 실행 방지 (3차에서 활용) |
| **Testcontainers** | 테스트용 Docker 컨테이너 자동 관리 |
| **BCrypt** | 비밀번호 해싱 (Spring Security 내장) |

### 왜 이 기술을 선택했는가?

- **PostgreSQL**: `SELECT FOR UPDATE`(비관적 락)를 네이티브로 지원. MySQL 대비 MVCC 구현이 더 정교
- **Spring Retry**: 낙관적 락 충돌 시 재시도 로직을 어노테이션 하나로 선언적으로 처리
- **Redis + Redisson**: 분산 환경에서 DB 락의 한계를 넘어서기 위해 (3차)
- **Kafka**: 예매 만료 시 좌석 반환을 이벤트 기반으로 처리. 서버 장애 시에도 이벤트 유실 방지 (3차)

---

## 3. 인프라 구성 (Docker Compose)

> 파일: `docker-compose.yml`

```yaml
services:
  postgres:     # 메인 데이터베이스
    image: postgres:16
    ports: ["5432:5432"]

  redis:        # 캐시 + 분산 락 + 대기열
    image: redis:7
    ports: ["6379:6379"]

  kafka:        # 이벤트 메시징
    image: apache/kafka:3.9.0
    ports: ["9092:9092", "29092:29092"]
    # KRaft 모드 (Zookeeper 없이 단독 실행)

  kafka-ui:     # Kafka 모니터링 웹 UI
    image: provectuslabs/kafka-ui:latest
    ports: ["8090:8080"]
```

### 실행 방법
```bash
docker compose up -d        # 백그라운드 실행
docker compose ps           # 상태 확인
docker compose down         # 중지
docker compose down -v      # 중지 + 데이터 삭제
```

### Kafka KRaft 모드란?
기존 Kafka는 Zookeeper가 필수였지만, KRaft 모드에서는 Kafka 자체가 컨트롤러 역할을 수행합니다.
`KAFKA_PROCESS_ROLES: broker,controller` — 하나의 노드가 브로커와 컨트롤러를 겸합니다.

### Healthcheck의 의미
```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U concert -d concert_booking"]
  interval: 5s    # 5초마다 확인
  timeout: 5s     # 5초 안에 응답 없으면 실패
  retries: 5      # 5회 실패하면 unhealthy
```
`depends_on`의 `condition: service_healthy`와 함께 사용하면 의존 서비스가 완전히 준비된 후에만 시작합니다.

---

## 4. 패키지 구조 — 왜 이렇게 나눴는가

```
src/main/java/com/concert/booking/
├── config/              # 설정 클래스 (Security, RetryConfig, DataInitializer)
├── controller/          # HTTP 요청 수신 → Service 호출 → 응답 반환
├── service/
│   ├── auth/            # 인증 (회원가입, 로그인, UserDetails)
│   ├── concert/         # 콘서트 조회
│   ├── reservation/     # 예매 (전략 패턴 인터페이스 + 2개 구현체)
│   └── payment/         # 결제
├── domain/              # Entity (JPA 매핑 객체) + Enum
├── repository/          # DB 접근 (Spring Data JPA)
├── dto/                 # 요청/응답 데이터 객체
└── common/
    ├── jwt/             # JWT 토큰 처리
    └── exception/       # 예외 클래스 + 전역 핸들러
```

### 계층별 역할 (레이어드 아키텍처)

```
[Client] → Controller → Service → Repository → [Database]
              ↕             ↕           ↕
             DTO          Domain      Entity
```

| 계층 | 역할 | 규칙 |
|------|------|------|
| **Controller** | HTTP 요청/응답 처리 | 비즈니스 로직 X, Service 호출만 |
| **Service** | 비즈니스 로직 | 트랜잭션 관리, 도메인 객체 조합 |
| **Repository** | DB 접근 | JPA 쿼리, 락 쿼리 |
| **Domain** | 핵심 비즈니스 규칙 | 상태 전이 검증 (Entity 내부) |
| **DTO** | 데이터 전송 | Entity 직접 노출 방지 |

### 왜 Entity를 직접 응답하지 않는가?

```java
// BAD: Entity 직접 반환 → 내부 구조 노출, 순환 참조, 불필요한 필드
@GetMapping
public Concert getConcert() { return concertRepository.findById(id); }

// GOOD: DTO로 변환 → 필요한 필드만, API 변경에 유연
@GetMapping
public ConcertResponse getConcert() { return ConcertResponse.from(concert); }
```

**더 구체적인 이유:**
1. **보안**: Entity에는 password 같은 민감 정보가 있을 수 있음
2. **순환 참조**: Entity의 양방향 관계가 JSON 직렬화 시 무한루프 발생
3. **API 안정성**: DB 스키마 변경이 API 응답에 직접 영향을 미치는 것 방지
4. **필요한 데이터만**: 좌석 목록 API에서 좌석의 version 필드까지 노출할 필요 없음

---

## 5. 데이터베이스 설계 (7개 테이블)

> 파일: `src/main/resources/schema.sql`

### ERD (Entity Relationship Diagram)

```
┌─────────┐     ┌──────────────────┐     ┌──────────┐
│  users  │     │ concert_schedules│     │ concerts │
├─────────┤     ├──────────────────┤     ├──────────┤
│ id (PK) │     │ id (PK)          │────→│ id (PK)  │
│ email   │     │ concert_id (FK)  │     │ title    │
│ password│     │ schedule_date    │     │ venue    │
│ nickname│     │ start_time       │     │ artist   │
└────┬────┘     │ total_seats      │     └──────────┘
     │          │ available_seats  │
     │          │ version          │ ← 낙관적 락용
     │          └────────┬─────────┘
     │                   │
     │          ┌────────┴─────────┐
     │          │      seats       │
     │          ├──────────────────┤
     │          │ id (PK)          │
     │          │ schedule_id (FK) │
     │          │ section / row /  │
     │          │ seat_number      │
     │          │ price / status   │ ←── AVAILABLE / HELD / RESERVED
     │          │ version          │ ← 낙관적 락용
     │          └────────┬─────────┘
     │                   │
┌────┴────────────────┐  │  ┌───────────────────┐
│    reservations     │  │  │ reservation_seats  │
├─────────────────────┤  │  ├───────────────────┤
│ id (PK)             │←─┼──│ reservation_id(FK)│
│ reservation_key(UUID│  └──│ seat_id (FK)      │
│ user_id (FK)        │     └───────────────────┘
│ schedule_id (FK)    │
│ status / amount     │              ┌──────────────┐
│ expires_at          │              │   payments   │
└─────────────────────┘──────────────│ id / amount  │
                                     │ payment_key  │
                                     │ reservation_id│
                                     └──────────────┘
```

### 테이블별 핵심 포인트

#### seats — version 컬럼의 의미
```sql
status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'  -- 상태 머신의 시작점
version BIGINT NOT NULL DEFAULT 0                 -- 낙관적 락용 버전 관리
```
**version이 하는 일:** 데이터가 수정될 때마다 version이 1씩 증가합니다.
두 트랜잭션이 동시에 같은 좌석을 수정하면, 먼저 커밋한 쪽이 version을 올리고,
나중에 커밋하는 쪽은 "내가 읽었을 때의 version과 다르다!"고 감지하여 실패합니다.
이것이 **낙관적 락**의 원리입니다. (14장에서 자세히 설명)

#### reservations — UUID를 별도로 쓰는 이유
```sql
reservation_key UUID NOT NULL UNIQUE  -- 외부 노출용 식별자
```
auto_increment PK는 `1, 2, 3`으로 추측 가능. `/api/reservations/3`이면 다른 사람이 `/api/reservations/2`를 시도할 수 있음. UUID는 추측 불가능합니다.

#### reservation_seats — 중간 테이블이 필요한 이유
1건의 예매에 최대 4석까지 선택 가능 → 예매:좌석 = **N:M 관계** → 중간 테이블 필수

### 인덱스 설계

```sql
CREATE INDEX idx_seats_schedule_status ON seats(schedule_id, status);
CREATE INDEX idx_reservations_user_id ON reservations(user_id);
CREATE INDEX idx_reservations_status_expires ON reservations(status, expires_at);
```

**인덱스가 없으면?**: 전체 테이블을 한 줄씩 읽어야 합니다 (Full Table Scan).
**인덱스가 있으면?**: 전화번호부처럼 정렬된 목록에서 빠르게 찾습니다.

**복합 인덱스에서 컬럼 순서가 중요한 이유:**
```
인덱스: (schedule_id, status)
→ WHERE schedule_id = 1                    ✅ 인덱스 사용
→ WHERE schedule_id = 1 AND status = 'A'   ✅ 인덱스 사용 (최적)
→ WHERE status = 'AVAILABLE'               ❌ 인덱스 사용 불가 (첫 번째 컬럼 없음)
```

---

## 6. 도메인 엔티티 — Rich Domain Model

### Anemic vs Rich Domain Model

```java
// ❌ Anemic Domain Model — Entity는 데이터만, 로직은 Service에
public class Seat {
    private SeatStatus status;
    public void setStatus(SeatStatus status) { this.status = status; }
}
// Service: seat.setStatus(SeatStatus.RESERVED);
// → AVAILABLE에서 바로 RESERVED? HELD를 건너뛰었는데 아무도 막지 못함!

// ✅ Rich Domain Model (이 프로젝트) — Entity가 스스로 상태를 관리
public class Seat {
    private SeatStatus status;
    public void hold() {
        if (this.status != SeatStatus.AVAILABLE)
            throw new IllegalStateException("예매 가능한 좌석이 아닙니다.");
        this.status = SeatStatus.HELD;
    }
    // setter 없음! → 잘못된 상태 변경 원천 차단
}
```

### Entity 공통 패턴

```java
@Entity
@Getter                                             // getter만 (setter 없음!)
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA용 (외부 사용 금지)
public class SomeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version private Long version;  // 낙관적 락

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public static SomeEntity create(...) { ... }  // 정적 팩토리 메서드
}
```

### `@Version` — 낙관적 락의 핵심

```java
@Version
private Long version;
```

JPA가 UPDATE 시 자동으로 생성하는 SQL:
```sql
UPDATE seats SET status = 'HELD', version = 1
WHERE id = 3 AND version = 0   -- 내가 읽었을 때의 version!
-- 0 rows updated → OptimisticLockException!
```

---

## 7. 좌석 상태 머신 (State Machine)

### Seat 상태 전이

```
    ┌─────────────┐
    │  AVAILABLE  │ ← 초기 상태 (예매 가능)
    └──────┬──────┘
           │ hold()     ← 예매 시 좌석 점유
           ▼
    ┌─────────────┐
    │    HELD     │ ← 5분간 임시 점유 (결제 대기)
    └──┬──────┬───┘
       │      │
  reserve()  release()
       │      │
       ▼      ▼
┌──────────┐ ┌─────────────┐
│ RESERVED │ │  AVAILABLE  │ ← 다시 예매 가능
└──────────┘ └─────────────┘
```

**핵심**: `AVAILABLE → RESERVED`로 직접 전이 **불가능**. 반드시 `HELD`를 거쳐야 합니다.

**왜 HELD 단계가 필요한가?**
```
HELD 없이 바로 RESERVED라면:
  예매 = 결제 완료. 결제 전에 좌석이 확정됨 → 결제 실패하면?

HELD 단계가 있으면:
  예매 → HELD (5분) → 결제 시 RESERVED / 미결제 시 AVAILABLE 복원
```
인터파크, 예스24 등 실제 예매 시스템과 동일한 패턴입니다.

### Reservation 상태 전이

```
    ┌─────────┐
    │ PENDING │ ← 예매 직후
    └──┬──┬──┬┘
       │  │  │
  confirm() cancel() expire()
       ▼     ▼       ▼
 CONFIRMED CANCELLED EXPIRED
```

---

## 8. JWT 인증 — 요청이 처리되기까지

### JWT란?

**JSON Web Token** — 서버가 클라이언트에게 발급하는 "디지털 신분증"입니다.

**비유**: 놀이공원 입장 팔찌
- 입장 시 한 번 확인하고 팔찌를 채워줌 (로그인 시 JWT 발급)
- 이후엔 팔찌만 보면 확인 끝 (매번 DB 조회 불필요)
- 위조 방지 표시 있음 (서명으로 위변조 감지)
- 퇴장 시간 정해져 있음 (만료 시간)

### 인증 흐름

```
1. 회원가입: POST /api/auth/signup → BCrypt 해싱 → DB 저장
2. 로그인:   POST /api/auth/login  → 비밀번호 검증 → JWT 발급
3. API 호출: Header: Authorization: Bearer {jwt}
4. 필터:     JwtAuthenticationFilter → 토큰 검증 → SecurityContext 설정
5. 컨트롤러: @AuthenticationPrincipal로 userId 사용
```

### BCrypt 해싱
```
원본: "password123"
해싱: "$2a$10$N9qo8uLOickgx2ZMRZoMye..."
```
- **단방향**: 해시 → 원본 복원 불가능
- **salt**: 같은 비밀번호도 매번 다른 해시 (레인보우 테이블 공격 방지)

---

## 9. 예매 흐름 — 핵심 비즈니스 로직

> 파일: `service/reservation/PessimisticLockReservationService.java`

```java
@Transactional
public ReservationResponse reserve(Long userId, ReservationRequest request) {
    // ① 사용자/스케줄 조회
    // ② 좌석 ID 정렬 (데드락 방지) — [5, 3] → [3, 5]
    // ③ 비관적 락으로 좌석 조회 (SELECT FOR UPDATE)
    // ④ All-or-Nothing 검증 — 2석 요청, 1석만 가능 → 전체 실패
    // ⑤ 좌석 HOLD 처리 — AVAILABLE → HELD
    // ⑥ 총 금액 계산
    // ⑦ 예매 생성 (PENDING, 5분 만료)
    // ⑧ 예매-좌석 매핑 (중간 테이블)
    // ⑨ 잔여 좌석 수 감소
    // COMMIT 또는 실패 시 ROLLBACK
}
```

### All-or-Nothing이란?

```
사용자가 좌석 3, 5를 함께 예매 요청
→ 좌석 3: AVAILABLE ✅
→ 좌석 5: HELD ❌ (이미 점유됨)
결과: 좌석 3도 예매하지 않음. 전체 실패!
```

**왜?**: 콘서트는 일행과 함께 감. 4석 중 2석만 예매되면 나머지 2명은?
→ "전부 아니면 전무"가 사용자 경험상 올바릅니다.

---

## 10. 결제 흐름

> 파일: `service/payment/PaymentService.java`

```java
@Transactional
public PaymentResponse pay(Long userId, PaymentRequest request) {
    // ① 예매 조회
    // ② 본인 확인 — 다른 사람의 예매 결제 불가
    // ③ 상태 확인 — PENDING만 결제 가능
    // ④ 만료 확인 — 5분 초과 시 결제 불가
    // ⑤ 결제 생성 (mock PG — 즉시 COMPLETED)
    // ⑥ 예매 확정: PENDING → CONFIRMED
    // ⑦ 좌석 확정: HELD → RESERVED
}
```

### 결제 전후 상태 변화
```
결제 전:  Reservation=PENDING, Seat=HELD
결제 후:  Reservation=CONFIRMED, Seat=RESERVED, Payment=COMPLETED
만료 시:  Reservation=EXPIRED, Seat=AVAILABLE (반환)
```

---

## 11. 예외 처리 설계

### 예외 계층 구조
```
RuntimeException
└── BusinessException (abstract)           ← HTTP 상태 코드 + 에러 코드
    ├── UnauthorizedException (401)
    ├── ReservationNotFoundException (404)
    ├── SeatNotAvailableException (409)
    ├── SoldOutException (409)
    ├── PaymentException (400)
    └── InvalidReservationStateException (400)
```

### GlobalExceptionHandler
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    // BusinessException → 각각의 HTTP 상태 코드 + { code, message, timestamp }
    // MethodArgumentNotValidException → 400
    // Exception → 500 (에러 로그 기록, 사용자에게는 내부 정보 비노출)
}
```

**통일된 에러 응답:**
```json
{ "code": "SEAT_NOT_AVAILABLE", "message": "선택한 좌석 중 이미 예매된 좌석이 있습니다.", "timestamp": "..." }
```

---

## 12. 동시성 문제란 무엇인가

이 프로젝트의 **핵심 주제**입니다.

### 일상 비유: 마지막 1개 남은 상품

```
쇼핑몰에서 운동화가 1개 남았습니다.
  사용자 A: "재고 확인" → 1개 남음 ✅ → "구매하기" 클릭
  사용자 B: "재고 확인" → 1개 남음 ✅ → "구매하기" 클릭
  (동시에 확인했으므로 둘 다 1개 남은 것으로 보임)

  사용자 A: 재고 1 → 0 (구매 완료) ✅
  사용자 B: 재고 0 → -1 (구매 완료?!) ❌
→ 1개 남은 운동화를 2명에게 판매 = 데이터 정합성 깨짐!
```

### 콘서트 예매에서의 동시성 문제

```
보호 장치 없이:
T1: SELECT * FROM seats WHERE id = 3;  → 'AVAILABLE' ✅
T2: SELECT * FROM seats WHERE id = 3;  → 'AVAILABLE' ✅
T1: UPDATE SET status = 'HELD';        → 성공
T2: UPDATE SET status = 'HELD';        → 성공?! (이미 HELD인데!)
→ 좌석 3이 2명에게 예매됨 = 한 좌석에 두 사람이 앉게 됨!
```

이 문제를 해결하는 2가지 전략:

| 전략 | 비유 | 구현 |
|------|------|------|
| **비관적 락** | "먼저 문 잠그고 들어가기" | 1차 |
| **낙관적 락** | "일단 들어가고, 나올 때 확인" | 2차 |

---

## 13. 전략 1: 비관적 락 (Pessimistic Lock)

> 파일: `service/reservation/PessimisticLockReservationService.java`

### 핵심 아이디어

**"다른 사람이 건드리지 못하게, 조회할 때부터 미리 잠근다"**

비유: 화장실 문 잠금
```
1. 들어가면서 문 잠금 (SELECT FOR UPDATE)
2. 볼 일 봄 (비즈니스 로직)
3. 나오면서 문 열림 (COMMIT)
다른 사람: 문 잠겨있으니 앞에서 대기 ⏳
```

### 비관적 락 적용 후

```
T1: SELECT ... FOR UPDATE WHERE id = 3;  → 🔒 락 획득
T2: SELECT ... FOR UPDATE WHERE id = 3;  → ⏳ 대기 (T1이 보유 중)
T1: UPDATE status = 'HELD'; COMMIT;      → 🔓 락 해제
T2: (락 획득) SELECT → status = 'HELD' → AVAILABLE 아님 → 실패!
→ 정확히 1명만 성공! ✅
```

### JPA에서 비관적 락 선언

```java
// 파일: repository/SeatRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)          // FOR UPDATE 추가
@Query("SELECT s FROM Seat s WHERE s.id IN :seatIds AND s.status = 'AVAILABLE' ORDER BY s.id")
List<Seat> findAllByIdInAndAvailableForUpdate(@Param("seatIds") List<Long> seatIds);
```

### 데드락 방지: ORDER BY s.id

```
데드락 (정렬 없을 때):
  T1: 좌석3 🔒 → 좌석5 🔒 시도 (대기)
  T2: 좌석5 🔒 → 좌석3 🔒 시도 (대기)
  → 서로 상대방이 가진 락을 기다림 = 영원히 대기! 💀

정렬 후 (항상 ID 오름차순):
  T1: 좌석3 🔒 → 좌석5 🔒 (성공)
  T2: 좌석3 🔒 시도 (대기) → 순환 없음 ✅
```

---

## 14. 전략 2: 낙관적 락 (Optimistic Lock)

> 파일: `service/reservation/OptimisticLockReservationService.java`

### 핵심 아이디어

**"충돌은 잘 안 일어날 거야. 일단 진행하고, 끝날 때 충돌을 확인하자"**

비유: 구글 독스 동시 편집
```
비관적 락 = 워드 파일을 한 명만 열 수 있음 (다른 사람은 대기)
낙관적 락 = 구글 독스처럼 여러 명이 동시에 편집
           → 같은 줄을 수정하면 "충돌 발생!" → 다시 시도
```

### 동작 원리: @Version 필드

```
조회: SELECT * FROM seats WHERE id = 3; → { status: 'AVAILABLE', version: 0 }

수정 (JPA가 자동 생성):
  UPDATE seats SET status = 'HELD', version = 1
  WHERE id = 3 AND version = 0    ← 내가 읽었을 때의 version!

  성공: 1 row updated (version: 0 → 1) ✅
  실패: 0 rows updated (다른 트랜잭션이 이미 version을 올림) → 예외! ❌
```

### 낙관적 락에서의 동시 접근

```
T1: SELECT → version = 0
T2: SELECT → version = 0 (락 없으므로 즉시!)

T1: UPDATE ... WHERE version=0 → 성공 ✅ (version: 0 → 1)
T2: UPDATE ... WHERE version=0 → 0 rows! → OptimisticLockException ❌
    → @Retryable이 자동 재시도
    → 재조회: status='HELD' → AVAILABLE 아님 → 최종 실패!
```

### Spring Retry — 자동 재시도 메커니즘

#### 설정
```java
// config/RetryConfig.java
@Configuration
@EnableRetry        // Spring Retry 기능 활성화
public class RetryConfig { }
```

```kotlin
// build.gradle.kts
implementation("org.springframework.retry:spring-retry")
implementation("org.springframework:spring-aspects")
```

#### 적용
```java
@Retryable(
    retryFor = ObjectOptimisticLockingFailureException.class,  // 이 예외 발생 시
    maxAttempts = 3,                                            // 최대 3번 시도
    backoff = @Backoff(delay = 50, multiplier = 2)              // 50ms → 100ms → 200ms
)
@Transactional
public ReservationResponse reserve(Long userId, ReservationRequest request) {
    // 락 없는 조회 사용
    List<Seat> seats = seatRepository.findAllByIdInAndAvailable(sortedSeatIds);
    // ... 나머지 비즈니스 로직은 비관적 락과 동일
}
```

#### 재시도 흐름
```
1차 시도 → 트랜잭션 시작 → 실행 → 커밋 시 version 충돌! → 롤백
  50ms 대기
2차 시도 → 새 트랜잭션 → 최신 데이터 조회 → 성공 or 충돌
  100ms 대기
3차 시도 (마지막) → 성공 or 예외 전파
```

#### @Retryable과 @Transactional의 순서

```
Caller → @Retryable → @Transactional → 실제 메서드
         (바깥)         (안쪽)

✅ 올바른 동작:
  재시도할 때마다 새 트랜잭션이 열림 → 최신 DB 데이터로 다시 시도

❌ 순서가 반대라면:
  하나의 트랜잭션 안에서 재시도 → 이미 롤백된 트랜잭션에서 재시도 → 실패!
```

### 비관적 락 서비스와의 코드 차이 (단 2가지)

```java
// 비관적 락
@Transactional                          // 트랜잭션만
seatRepository.findAllByIdInAndAvailableForUpdate(...)  // FOR UPDATE

// 낙관적 락
@Retryable(...) @Transactional          // 재시도 + 트랜잭션
seatRepository.findAllByIdInAndAvailable(...)            // 일반 SELECT
```

---

## 15. 비관적 락 vs 낙관적 락 — 완전 비교

| | 비관적 락 | 낙관적 락 |
|---|---|---|
| **비유** | 화장실 문 잠금 | 구글 독스 동시 편집 |
| **SQL** | `SELECT ... FOR UPDATE` | 일반 `SELECT` |
| **충돌 감지** | 조회 시 (미리 차단) | 커밋 시 (`@Version`) |
| **충돌 시** | 대기 (blocking) | 예외 + 재시도 |
| **재시도** | 불필요 | `@Retryable` 필요 |
| **DB 커넥션** | 락 대기 중에도 점유 | 짧게 점유 |
| **처리량** | 낮음 (직렬) | 높음 (병렬 시도) |

### 어떤 상황에 어떤 락?

```
충돌이 많은 경우 (인기 좌석):
  비관적 락 ✅ — 어차피 충돌하니 미리 잠궈서 확실하게
  낙관적 락 ❌ — 대부분 실패 + 재시도 = 오히려 비효율

충돌이 적은 경우 (일반 게시글 수정):
  비관적 락 ❌ — 불필요한 락으로 성능만 저하
  낙관적 락 ✅ — 대부분 성공, 가끔 재시도
```

### 10명 동시 요청 시 실행 흐름 비교

```
비관적 락:
  T1: 🔒→처리→성공→🔓
  T2: ⏳⏳⏳⏳⏳→🔒→실패
  T3: ⏳⏳⏳⏳⏳⏳⏳⏳→🔒→실패
  특징: 순서대로 하나씩 (직렬). 대기 시간이 길어질 수 있음.

낙관적 락:
  T1: SELECT→처리→COMMIT(성공✅)
  T2: SELECT→처리→COMMIT(version충돌!)→재시도→실패
  T3: SELECT→처리→COMMIT(version충돌!)→재시도→실패
  특징: 동시에 진행 (병렬). 충돌 많으면 재시도 증가.
```

---

## 16. 전략 패턴으로 락 전략 교체하기

### 구조

```java
// 인터페이스 (계약서)
public interface ReservationService {
    ReservationResponse reserve(Long userId, ReservationRequest request);
    void cancelReservation(Long userId, Long reservationId);
    // ...
}

// 구현체 1: 비관적 락
@Service @Primary  // ← 기본 구현체
public class PessimisticLockReservationService implements ReservationService { }

// 구현체 2: 낙관적 락
@Service           // ← @Primary 없음
public class OptimisticLockReservationService implements ReservationService { }
```

### Controller는 인터페이스에만 의존

```java
@RestController
public class ReservationController {
    private final ReservationService reservationService;  // 인터페이스 타입!
    // @Primary가 붙은 비관적 락이 자동 주입
    // Controller는 어떤 락 전략인지 모르고, 알 필요도 없음!
}
```

### 특정 구현체 선택: @Qualifier

```java
@Qualifier("optimisticLockReservationService")  // 빈 이름으로 지정
private ReservationService reservationService;
```

### 장점

새 전략 추가 시 **기존 코드 수정 없이** 새 클래스만 만들면 됩니다.
이것이 SOLID의 **OCP(Open-Closed Principle)**: "확장에는 열려있고, 수정에는 닫혀있다"

---

## 17. 테스트 전략과 Testcontainers

### Testcontainers란?

테스트 실행 시 **실제 Docker 컨테이너**(PostgreSQL)를 자동으로 띄우고, 끝나면 제거합니다.

```java
// test/config/TestContainersConfig.java
@TestConfiguration
public class TestContainersConfig {
    @Bean
    @ServiceConnection  // datasource URL을 자동 교체
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("concert_booking_test");
    }
}
```

### 왜 H2가 아닌 Testcontainers?

| | H2 | Testcontainers |
|---|---|---|
| 속도 | 빠름 | 약간 느림 |
| `FOR UPDATE` | 다르게 동작 | 완벽 지원 |
| 호환성 | SQL 문법 차이 | 실제 PostgreSQL |
| 신뢰도 | "로컬에서 됐는데 서버에서 안 돼요" | 동일 DB 엔진 |

이 프로젝트의 핵심인 `SELECT FOR UPDATE`는 H2에서 제대로 테스트 불가.

### 테스트 프로파일

```yaml
# test/resources/application-test.yml
spring:
  autoconfigure:
    exclude:
      - KafkaAutoConfiguration       # 테스트에서 Kafka 불필요
      - RedisAutoConfiguration        # 테스트에서 Redis 불필요
```

### 테스트 목록 (총 9개)

| 테스트 | 파일 | 검증 내용 |
|--------|------|-----------|
| 컨텍스트 로드 | `ConcertBookingApplicationTest` | Spring 컨텍스트 정상 기동 |
| 인증 통합 (4건) | `AuthIntegrationTest` | 회원가입, 로그인, 비밀번호 오류, 중복 이메일 |
| 예매 E2E (2건) | `BookingFlowIntegrationTest` | 전체 예매→결제 흐름 + 취소 흐름 |
| **비관적 락** 동시성 | `ConcurrencyIntegrationTest` | 10명 동시 예매 → 1명만 성공 |
| **낙관적 락** 동시성 | `OptimisticLockConcurrencyTest` | 10명 동시 예매 → 1명만 성공 |

---

## 18. 동시성 테스트 — 왜 1명만 성공하는가

> 파일: `test/integration/ConcurrencyIntegrationTest.java`
> 파일: `test/integration/OptimisticLockConcurrencyTest.java`

### 테스트 구조 (두 테스트 모두 동일 패턴)

```java
int threadCount = 10;
ExecutorService executor = Executors.newFixedThreadPool(threadCount);
CountDownLatch latch = new CountDownLatch(threadCount);
AtomicInteger successCount = new AtomicInteger(0);

for (int i = 0; i < threadCount; i++) {
    executor.submit(() -> {
        try {
            reservationService.reserve(userId, request);  // 같은 좌석!
            successCount.incrementAndGet();
        } catch (Exception e) {
            failCount.incrementAndGet();
        } finally {
            latch.countDown();  // "나 끝났어" 신호
        }
    });
}

latch.await();  // 10개 스레드 모두 끝날 때까지 대기
assertThat(successCount.get()).isEqualTo(1);  // 정확히 1명!
assertThat(failCount.get()).isEqualTo(9);
```

### 차이점: 서비스 주입 방식

```java
// 비관적 락 테스트 — @Primary가 자동 주입
@Autowired
private ReservationService reservationService;

// 낙관적 락 테스트 — @Qualifier로 명시
@Autowired
@Qualifier("optimisticLockReservationService")
private ReservationService reservationService;
```

### 핵심 동시성 도구 설명

#### ExecutorService — 스레드 풀
```java
ExecutorService executor = Executors.newFixedThreadPool(10);
// 10개 스레드를 미리 만들어둔 "작업자 풀"
// executor.submit(task): 작업을 풀에 제출하면 남는 스레드가 실행
```

#### CountDownLatch — "모두 끝날 때까지 기다려"
```java
CountDownLatch latch = new CountDownLatch(10);  // 카운트: 10
latch.countDown();  // 각 스레드 끝에서: 카운트 -1
latch.await();      // 메인 스레드: 카운트가 0이 될 때까지 대기
```

#### AtomicInteger — 스레드 안전한 카운터
```java
AtomicInteger count = new AtomicInteger(0);
count.incrementAndGet();  // CAS 연산으로 원자적 증가
```
일반 `int`는 여러 스레드가 동시에 증가시키면 값이 꼬입니다 (Lost Update).

---

## 19. Spring Boot 핵심 개념 정리

### @Transactional

```java
@Transactional        // 메서드 전체가 하나의 트랜잭션. 예외 시 ROLLBACK
@Transactional(readOnly = true)  // SELECT만. Dirty Checking 건너뜀 → 성능 향상
```

### Dirty Checking (변경 감지)

```java
@Transactional
public void cancel(...) {
    Reservation r = reservationRepository.findById(id).orElseThrow(...);
    r.cancel();  // status 변경
    // save() 호출 안 해도 JPA가 자동으로 UPDATE 실행!
    // → 영속성 컨텍스트가 스냅샷과 현재 상태를 비교
}
```

### @RequiredArgsConstructor + final = 생성자 주입

```java
@Service
@RequiredArgsConstructor  // Lombok: final 필드에 대한 생성자 자동 생성
public class PaymentService {
    private final ReservationRepository reservationRepository;  // final = 필수
}
```

### Java Record — DTO에 최적

```java
public record LoginRequest(@NotBlank String email, @NotBlank String password) { }
// 자동: getter(email(), password()), 생성자, equals, hashCode, toString, 불변
```

### open-in-view: false

```yaml
jpa:
  open-in-view: false  # DB 커넥션 점유 최소화 (운영 환경 권장)
```

---

## 20. 설정 파일 해설

### application.yml 핵심

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none        # 테이블 자동 생성 안 함 (schema.sql로 관리)
    properties:
      hibernate:
        default_batch_fetch_size: 100  # N+1 문제 완화
    open-in-view: false      # DB 커넥션 점유 최소화

  sql:
    init:
      mode: always           # 매 시작마다 schema.sql 실행 (IF NOT EXISTS로 멱등성)

jwt:
  secret: local-dev-secret-key-...   # HMAC-SHA256 키 (256bit 이상)
  expiration: 3600000                # 1시간 (밀리초)
```

### ddl-auto 옵션

| 값 | 동작 | 사용 시기 |
|:---:|------|------|
| `none` | 아무것도 안 함 | **운영 환경 (이 프로젝트)** |
| `validate` | 스키마 일치 검증 | 운영 전 검증 |
| `update` | 변경분 자동 반영 | 개발 초기 |
| `create` | DROP + CREATE | 절대 사용 X |

---

## 21. 디자인 패턴과 설계 원칙

### 1. 전략 패턴 → 16장 참고

### 2. 정적 팩토리 메서드
```java
Seat.create(schedule, "VIP", 1, 1, 150000);  // 의미 있는 이름 + 필수 값 강제
```

### 3. DTO 변환 패턴
```java
public record ConcertResponse(Long id, String title, ...) {
    public static ConcertResponse from(Concert concert) { ... }  // 변환 책임을 DTO에
}
```

### 4. DataInitializer
```java
@Component
@Profile("!test")  // 테스트에서는 비활성화
public class DataInitializer implements ApplicationRunner {
    public void run(ApplicationArguments args) {
        if (concertRepository.count() > 0) return;  // 멱등성
        // 테스트 데이터 자동 생성
    }
}
```

---

## 22. 자주 묻는 질문 (FAQ)

### Q1. 왜 setter를 안 쓰나요?
Setter는 아무 곳에서나, 아무 값으로 상태를 바꿀 수 있어 위험합니다.
`seat.hold()`처럼 의미 있는 메서드로 상태 검증 + 전이를 강제합니다.

### Q2. 비관적 락과 낙관적 락 중 뭐가 좋나요?
정답은 없습니다. 충돌이 많으면 비관적 락, 적으면 낙관적 락이 유리합니다.
이 프로젝트에서 두 가지를 모두 구현한 이유가 바로 이 비교를 위해서입니다.

### Q3. 왜 schema.sql로 테이블을 만드나요?
`ddl-auto: update`는 컬럼 삭제/이름 변경을 감지 못하고, 인덱스를 자동 생성하지 않습니다.
직접 SQL을 작성하면 정확히 어떤 DDL이 실행되는지 알 수 있습니다.

### Q4. @Retryable이 @Transactional보다 바깥에 있어야 하는 이유는?
재시도할 때 **새 트랜잭션**이 열려야 최신 데이터를 읽을 수 있기 때문입니다.
같은 트랜잭션 안에서 재시도하면 이미 롤백 마킹된 상태에서 실패합니다.

### Q5. Testcontainers가 H2보다 좋은 이유는?
`SELECT FOR UPDATE` 비관적 락은 H2에서 제대로 테스트할 수 없습니다.
실제 PostgreSQL과 동일한 환경에서 테스트해야 신뢰할 수 있습니다.

### Q6. @Primary 없이 구현체를 선택하려면?
`@Qualifier("빈이름")`으로 명시합니다. 빈 이름은 클래스 첫 글자를 소문자로 바꾼 것입니다.
예: `OptimisticLockReservationService` → `optimisticLockReservationService`

### Q7. 왜 예매할 때 schedule.decreaseAvailableSeats()를 호출하나요?
매번 `SELECT COUNT(*)`를 실행하면 느립니다. `available_seats` 필드를 미리 갱신하면 O(1)로 조회 가능합니다. 이것을 **역정규화(Denormalization)**라고 합니다.

---

## 부록: 전체 API 호출 예시

```bash
# 1. 회원가입
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"password123","nickname":"테스터"}'

# 2. 로그인 → JWT 토큰
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"password123"}' | jq -r '.token')

# 3. 콘서트 목록
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/concerts

# 4. 좌석 조회
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/concerts/1/schedules/1/seats

# 5. 좌석 예매
curl -X POST http://localhost:8080/api/reservations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"scheduleId":1,"seatIds":[1,2]}'

# 6. 결제
curl -X POST http://localhost:8080/api/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reservationId":1}'
```

---

> 이 문서에서 다루지 않은 내용 (3차에서 구현 예정):
> - Redis 분산 락 (Redisson)
> - Redis 대기열 + SSE 실시간 알림
> - Kafka 이벤트 기반 좌석 반환
> - 만료 스케줄러 (ShedLock)
> - k6 부하 테스트 + 3가지 락 전략 성능 비교
