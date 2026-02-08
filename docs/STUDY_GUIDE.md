# 콘서트 좌석 예매 시스템 — 완전 학습 가이드

> 이 문서 하나로 프로젝트의 모든 것을 이해할 수 있도록 작성했습니다.
> 코드를 직접 따라가며 읽으면 가장 효과적입니다.

---

## 목차

1. [프로젝트 한 줄 요약](#1-프로젝트-한-줄-요약)
2. [기술 스택과 각각의 역할](#2-기술-스택과-각각의-역할)
3. [인프라 구성 (Docker Compose)](#3-인프라-구성-docker-compose)
4. [패키지 구조 — 왜 이렇게 나눴는가](#4-패키지-구조--왜-이렇게-나눴는가)
5. [데이터베이스 설계 (7개 테이블)](#5-데이터베이스-설계-7개-테이블)
6. [도메인 엔티티 — Rich Domain Model](#6-도메인-엔티티--rich-domain-model)
7. [좌석 상태 머신 (State Machine)](#7-좌석-상태-머신-state-machine)
8. [JWT 인증 — 요청이 처리되기까지](#8-jwt-인증--요청이-처리되기까지)
9. [예매 흐름 — 핵심 비즈니스 로직](#9-예매-흐름--핵심-비즈니스-로직)
10. [비관적 락 — 동시성 제어의 핵심](#10-비관적-락--동시성-제어의-핵심)
11. [결제 흐름](#11-결제-흐름)
12. [예외 처리 설계](#12-예외-처리-설계)
13. [테스트 전략](#13-테스트-전략)
14. [동시성 테스트 — 왜 1명만 성공하는가](#14-동시성-테스트--왜-1명만-성공하는가)
15. [Spring Boot 핵심 개념 정리](#15-spring-boot-핵심-개념-정리)
16. [설정 파일 해설](#16-설정-파일-해설)
17. [디자인 패턴과 설계 원칙](#17-디자인-패턴과-설계-원칙)
18. [자주 묻는 질문 (FAQ)](#18-자주-묻는-질문-faq)

---

## 1. 프로젝트 한 줄 요약

**"1만 명이 동시에 1,000석을 예매할 때, 데이터 정합성을 어떻게 보장할 것인가?"**

이 프로젝트는 콘서트 좌석 예매 시스템을 만들면서 **동시성 제어**를 깊이 있게 다루는 백엔드 포트폴리오입니다.

### 전체 사용자 흐름

```
회원가입 → 로그인(JWT 발급) → 콘서트 목록 조회 → 스케줄 선택 → 좌석 조회
→ 좌석 선택(최대 4석) → 예매(좌석 HOLD, 5분 타이머) → 결제 → 예매 확정
```

### 현재 구현된 범위 (1차 MVP)

| 기능 | 상태 |
|------|------|
| 회원가입/로그인 (JWT) | 완료 |
| 콘서트/스케줄/좌석 조회 | 완료 |
| 좌석 예매 (비관적 락) | 완료 |
| 결제 (mock PG) | 완료 |
| 예매 취소 | 완료 |
| 통합 테스트 + 동시성 테스트 | 완료 |

---

## 2. 기술 스택과 각각의 역할

### 핵심 스택

| 기술 | 버전 | 이 프로젝트에서의 역할 |
|------|------|----------------------|
| **Java** | 21 | 메인 언어. Virtual Thread, Record 등 최신 기능 활용 |
| **Spring Boot** | 3.4.1 | 웹 프레임워크. 자동 설정, DI, AOP, 트랜잭션 관리 |
| **Spring Data JPA** | - | ORM. Entity ↔ DB 테이블 매핑, Repository 자동 구현 |
| **Spring Security** | - | 인증/인가. JWT 필터 체인 구성 |
| **PostgreSQL** | 16 | 메인 DB. 비관적 락(`SELECT FOR UPDATE`) 지원 |
| **Redis** | 7 | 캐시, 분산 락, 대기열 (2차 이후 구현 예정) |
| **Kafka** | 3.9 | 이벤트 기반 처리 (2차 이후 구현 예정) |

### 라이브러리

| 라이브러리 | 역할 |
|-----------|------|
| **jjwt** (0.12.6) | JWT 토큰 생성/검증 (HMAC-SHA256) |
| **Lombok** | 보일러플레이트 코드 제거 (`@Getter`, `@RequiredArgsConstructor` 등) |
| **Redisson** (3.40.2) | Redis 분산 락 클라이언트 (2차 이후 활용) |
| **ShedLock** (6.2.0) | 스케줄러 중복 실행 방지 (2차 이후 활용) |
| **Testcontainers** | 테스트용 Docker 컨테이너 자동 관리 |
| **BCrypt** | 비밀번호 해싱 (Spring Security 내장) |

### 왜 이 기술을 선택했는가?

- **PostgreSQL**: `SELECT FOR UPDATE`(비관적 락)를 네이티브로 지원. MySQL 대비 MVCC 구현이 더 정교
- **Redis + Redisson**: 분산 환경에서 DB 락의 한계를 넘어서기 위해 (2차 이후)
- **Kafka**: 예매 만료 시 좌석 반환을 이벤트 기반으로 처리. Spring Event와 달리 서버 장애 시에도 이벤트가 유실되지 않음 (2차 이후)

---

## 3. 인프라 구성 (Docker Compose)

> 파일: `docker-compose.yml`

```yaml
services:
  postgres:     # 메인 데이터베이스
    image: postgres:16
    ports: ["5432:5432"]
    # POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD 설정

  redis:        # 캐시 + 분산 락 + 대기열
    image: redis:7
    ports: ["6379:6379"]
    # maxmemory 128mb, LRU 정책

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
├── config/              # 설정 클래스 (Security, DataInitializer)
├── controller/          # HTTP 요청 수신 → Service 호출 → 응답 반환
├── service/
│   ├── auth/            # 인증 (회원가입, 로그인, UserDetails)
│   ├── concert/         # 콘서트 조회
│   ├── reservation/     # 예매 (전략 패턴 인터페이스 + 구현체)
│   └── payment/         # 결제
├── domain/              # Entity (JPA 매핑 객체) + Enum
├── repository/          # DB 접근 (Spring Data JPA)
├── dto/                 # 요청/응답 데이터 객체
│   ├── auth/
│   ├── concert/
│   ├── reservation/
│   └── payment/
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
     │          │ version          │
     │          └────────┬─────────┘
     │                   │
     │          ┌────────┴─────────┐
     │          │      seats       │
     │          ├──────────────────┤
     │          │ id (PK)          │
     │          │ schedule_id (FK) │
     │          │ section          │
     │          │ row_number       │
     │          │ seat_number      │
     │          │ price            │
     │          │ status           │ ←── AVAILABLE / HELD / RESERVED
     │          │ version          │
     │          └────────┬─────────┘
     │                   │
┌────┴────────────────┐  │  ┌───────────────────┐
│    reservations     │  │  │ reservation_seats  │
├─────────────────────┤  │  ├───────────────────┤
│ id (PK)             │←─┼──│ reservation_id(FK)│
│ reservation_key(UUID│  └──│ seat_id (FK)      │
│ user_id (FK)        │     └───────────────────┘
│ schedule_id (FK)    │
│ status              │ ←── PENDING / CONFIRMED / CANCELLED / EXPIRED
│ total_amount        │
│ expires_at          │              ┌──────────────┐
│ created_at          │              │   payments   │
└─────────────────────┘──────────────│ id (PK)      │
                                     │ payment_key  │
                                     │ reservation_id│
                                     │ amount       │
                                     │ status       │
                                     └──────────────┘
```

### 테이블별 핵심 포인트

#### users
```sql
email VARCHAR(255) NOT NULL UNIQUE  -- 이메일로 로그인하므로 UNIQUE 필수
password VARCHAR(255) NOT NULL      -- BCrypt 해싱된 비밀번호 (60자)
```

#### seats
```sql
status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'  -- 상태 머신의 시작점
version BIGINT NOT NULL DEFAULT 0                 -- 낙관적 락용 (2차에서 활용)
UNIQUE(schedule_id, section, row_number, seat_number)  -- 같은 좌석 중복 방지
```
**왜 status를 VARCHAR로?**: Enum 타입 대신 문자열을 쓰면 DB에서 직접 조회할 때 가독성이 좋고, Enum 값 추가 시 DDL 변경이 불필요합니다.

#### reservations
```sql
reservation_key UUID NOT NULL UNIQUE  -- 외부 노출용 식별자 (PK 대신)
expires_at TIMESTAMP                  -- NULL이면 만료 없음 (CONFIRMED 후 NULL)
```
**왜 UUID를 별도로?**: auto_increment PK는 추측 가능하므로, 외부에 노출할 때는 UUID를 사용합니다.

#### reservation_seats (중간 테이블)
```sql
UNIQUE(reservation_id, seat_id)  -- 같은 예매에 같은 좌석 중복 매핑 방지
```
**왜 필요한가?**: 1건의 예매에 최대 4석까지 선택할 수 있으므로, 예매와 좌석은 **다대다(N:M)** 관계입니다.
중간 테이블 없이는 이 관계를 표현할 수 없습니다.

### 인덱스 설계

```sql
-- 좌석 조회 최적화: "이 스케줄의 AVAILABLE 좌석 목록"
CREATE INDEX idx_seats_schedule_status ON seats(schedule_id, status);

-- 내 예매 목록 조회
CREATE INDEX idx_reservations_user_id ON reservations(user_id);

-- 만료된 예매 조회: "PENDING이면서 expires_at이 지난 예매"
CREATE INDEX idx_reservations_status_expires ON reservations(status, expires_at);
```

**복합 인덱스의 원리**: `(schedule_id, status)` 인덱스는 `WHERE schedule_id = 1 AND status = 'AVAILABLE'`
쿼리에서 두 조건을 한 번에 필터링합니다. 순서가 중요합니다 — 카디널리티가 높은(값이 다양한) 컬럼을 앞에 둡니다.

---

## 6. 도메인 엔티티 — Rich Domain Model

### Anemic vs Rich Domain Model

```java
// Anemic Domain Model (나쁜 예) — Entity는 데이터만, 로직은 Service에
public class Seat {
    private SeatStatus status;
    public void setStatus(SeatStatus status) { this.status = status; } // 아무나 바꿀 수 있음
}

// Rich Domain Model (이 프로젝트) — Entity가 스스로 상태를 관리
public class Seat {
    private SeatStatus status;

    public void hold() {
        if (this.status != SeatStatus.AVAILABLE) {  // 스스로 검증
            throw new IllegalStateException("예매 가능한 좌석이 아닙니다.");
        }
        this.status = SeatStatus.HELD;  // 허용된 전이만 가능
    }
    // setter 없음! → 잘못된 상태 변경 원천 차단
}
```

**장점**: 비즈니스 규칙이 Entity 안에 캡슐화되어, Service가 아무리 복잡해져도 "좌석이 잘못된 상태로 바뀌는 것"은 불가능합니다.

### Entity 공통 패턴

모든 Entity는 다음 패턴을 따릅니다:

```java
@Entity
@Table(name = "테이블명")
@Getter                                         // (1) getter만 제공
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // (2) 기본 생성자는 JPA용 (외부 사용 금지)
public class SomeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // (3) DB auto_increment
    private Long id;

    // ... 필드들 ...

    @PrePersist
    protected void onCreate() {                          // (4) 생성 시간 자동 설정
        this.createdAt = LocalDateTime.now();
    }

    public static SomeEntity create(...) {               // (5) 정적 팩토리 메서드
        SomeEntity entity = new SomeEntity();
        entity.field = value;
        return entity;
    }
}
```

#### (1) `@Getter`만, Setter 없음
- Setter가 있으면 어디서든 상태를 바꿀 수 있어 버그 추적이 어렵습니다.
- 상태 변경은 반드시 **의미 있는 메서드**(`hold()`, `confirm()` 등)를 통해서만.

#### (2) `@NoArgsConstructor(access = PROTECTED)`
- JPA는 리플렉션으로 객체를 생성하므로 기본 생성자가 필요합니다.
- `PROTECTED`로 외부에서 `new Entity()` 호출을 차단합니다.

#### (3) `GenerationType.IDENTITY`
- PostgreSQL의 `BIGSERIAL` (auto_increment)을 사용합니다.
- `save()` 호출 시 INSERT 즉시 실행되어 ID를 얻습니다.

#### (4) `@PrePersist`
- Entity가 DB에 저장되기 직전에 자동으로 호출됩니다.
- `createdAt`을 애플리케이션 레벨에서 설정합니다.

#### (5) 정적 팩토리 메서드
```java
// new + setter 방식 (나쁜 예)
Seat seat = new Seat();
seat.setSection("VIP");    // 빠뜨리면 null
seat.setPrice(150000);

// 정적 팩토리 (이 프로젝트)
Seat seat = Seat.create(schedule, "VIP", 1, 1, 150000);
// → 필수 값이 모두 인자로 강제됨. 빠뜨릴 수 없음.
```

### 연관관계 매핑

```java
// Seat → ConcertSchedule (N:1)
@ManyToOne(fetch = FetchType.LAZY)          // (1)
@JoinColumn(name = "schedule_id", nullable = false)  // (2)
private ConcertSchedule schedule;
```

#### (1) `FetchType.LAZY`
- **EAGER**: Seat를 조회하면 ConcertSchedule도 **항상** 함께 조회 (불필요한 쿼리 발생)
- **LAZY**: Seat를 조회할 때 ConcertSchedule은 프록시 객체. **실제 접근할 때만** 쿼리 실행
- **규칙**: 모든 `@ManyToOne`, `@OneToOne`은 반드시 `LAZY`로. (`@OneToMany`는 기본이 LAZY)

#### (2) `@JoinColumn`
- 이 Entity 테이블에 FK 컬럼명을 지정합니다.
- `nullable = false`: 좌석은 반드시 스케줄에 속해야 합니다.

### `@Version` — 낙관적 락 준비

```java
@Version
private Long version;
```
- JPA가 UPDATE 시 자동으로 `WHERE version = ?`을 추가합니다.
- 다른 트랜잭션이 먼저 수정했으면 `OptimisticLockException` 발생.
- **현재 MVP에서는 비관적 락을 사용**하지만, 2차에서 낙관적 락 비교를 위해 미리 선언해 두었습니다.

---

## 7. 좌석 상태 머신 (State Machine)

### Seat 상태 전이

```
    ┌─────────────┐
    │  AVAILABLE  │ ← 초기 상태
    └──────┬──────┘
           │ hold()     ← 예매 시
           ▼
    ┌─────────────┐
    │    HELD     │ ← 5분간 점유
    └──┬──────┬───┘
       │      │
reserve()  release()   ← 결제 완료 / 취소·만료
       │      │
       ▼      ▼
┌──────────┐ ┌─────────────┐
│ RESERVED │ │  AVAILABLE  │
└──────────┘ └─────────────┘
```

```java
// 각 메서드는 "현재 상태"를 검증한 후에만 전이를 허용합니다.
public void hold() {
    if (this.status != SeatStatus.AVAILABLE)        // AVAILABLE에서만 가능
        throw new IllegalStateException(...);
    this.status = SeatStatus.HELD;
}

public void reserve() {
    if (this.status != SeatStatus.HELD)             // HELD에서만 가능
        throw new IllegalStateException(...);
    this.status = SeatStatus.RESERVED;
}

public void release() {
    if (this.status != SeatStatus.HELD)             // HELD에서만 가능
        throw new IllegalStateException(...);
    this.status = SeatStatus.AVAILABLE;
}
```

**핵심**: `AVAILABLE → RESERVED`로 직접 전이하는 것은 **불가능**합니다. 반드시 `HELD`를 거쳐야 합니다.
이 설계 덕분에 "결제 전 5분 점유"라는 비즈니스 규칙이 코드 레벨에서 강제됩니다.

### Reservation 상태 전이

```
    ┌─────────┐
    │ PENDING │ ← 예매 직후
    └──┬──┬──┬┘
       │  │  │
confirm() cancel() expire()
       │  │  │
       ▼  ▼  ▼
┌──────────┐ ┌───────────┐ ┌─────────┐
│CONFIRMED │ │ CANCELLED │ │ EXPIRED │
└──────────┘ └───────────┘ └─────────┘
```

- **PENDING → CONFIRMED**: 결제 완료 시 (`reservation.confirm()`)
- **PENDING → CANCELLED**: 사용자가 직접 취소 (`reservation.cancel()`)
- **PENDING → EXPIRED**: 5분 내 미결제 시 (`reservation.expire()`)

```java
public void confirm() {
    if (this.status != ReservationStatus.PENDING)
        throw new IllegalStateException(...);
    this.status = ReservationStatus.CONFIRMED;
    this.expiresAt = null;  // 확정되면 만료 시간 제거
}
```

---

## 8. JWT 인증 — 요청이 처리되기까지

### JWT란?

**JSON Web Token** — 서버가 클라이언트에게 발급하는 "신분증"입니다.

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwiZW1haWwiOiJ0ZXN0QHRlc3QuY29tIiwiaWF0IjoxNzA3MzUwMDAwLCJleHAiOjE3MDczNTM2MDB9.서명값
│         HEADER        │                    PAYLOAD                    │   SIGNATURE   │
│   알고리즘: HS256      │  sub(userId): 1, email, 발급시간, 만료시간       │  비밀키로 서명  │
```

### 인증 흐름 전체 그림

```
1. 회원가입
   POST /api/auth/signup { email, password, nickname }
   → password를 BCrypt로 해싱 → DB에 저장

2. 로그인
   POST /api/auth/login { email, password }
   → DB에서 email로 User 조회
   → BCrypt.matches(입력 비밀번호, 저장된 해시) 검증
   → JwtProvider.createToken(userId, email) → JWT 문자열 반환

3. 인증이 필요한 API 호출
   GET /api/concerts
   Header: Authorization: Bearer eyJhbGci...

4. JwtAuthenticationFilter (모든 요청 전에 실행)
   → Authorization 헤더에서 "Bearer " 뒤의 토큰 추출
   → JwtProvider.validateToken(token) → 서명 검증 + 만료 확인
   → JwtProvider.getUserId(token) → userId 추출
   → UserDetailsService.loadUserByUsername(userId) → DB에서 User 조회
   → SecurityContext에 인증 정보 설정 → 이후 Controller에서 @AuthenticationPrincipal 사용 가능
```

### 코드 따라가기

#### (1) JwtProvider — 토큰 생성/검증

```java
// 파일: common/jwt/JwtProvider.java

// 생성
public String createToken(Long userId, String email) {
    return Jwts.builder()
            .subject(String.valueOf(userId))     // sub 클레임 = userId
            .claim("email", email)               // 커스텀 클레임
            .issuedAt(now)                       // 발급 시간
            .expiration(expiryDate)              // 만료 시간 (1시간)
            .signWith(secretKey)                 // HMAC-SHA256 서명
            .compact();                          // 문자열 생성
}

// 검증
public boolean validateToken(String token) {
    try {
        parseClaims(token);  // 서명 검증 + 만료 확인
        return true;
    } catch (JwtException | IllegalArgumentException e) {
        return false;        // 위변조 or 만료 → false
    }
}
```

#### (2) JwtAuthenticationFilter — 모든 요청을 가로채는 필터

```java
// 파일: common/jwt/JwtAuthenticationFilter.java

// OncePerRequestFilter: 요청당 딱 1번만 실행
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(...) {
        String token = resolveToken(request);        // "Bearer xxx" → "xxx"

        if (token != null && jwtProvider.validateToken(token)) {
            Long userId = jwtProvider.getUserId(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(String.valueOf(userId));

            // Spring Security의 인증 객체 생성
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

            // SecurityContext에 저장 → 이후 어디서든 인증 정보 접근 가능
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);  // 다음 필터로 진행
    }
}
```

#### (3) SecurityConfig — 필터 체인 설정

```java
// 파일: config/SecurityConfig.java

http
    .csrf(AbstractHttpConfigurer::disable)              // REST API이므로 CSRF 불필요
    .sessionManagement(session ->
        session.sessionCreationPolicy(STATELESS))       // 세션 사용 안 함 (JWT 기반)
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/**").permitAll()     // 인증 없이 접근 가능
        .anyRequest().authenticated()                    // 나머지는 JWT 필요
    )
    .addFilterBefore(jwtAuthenticationFilter,
        UsernamePasswordAuthenticationFilter.class);     // JWT 필터를 Spring 기본 필터 앞에 삽입
```

#### (4) Controller에서 인증 정보 사용

```java
// @AuthenticationPrincipal: SecurityContext에서 현재 로그인한 사용자 정보를 꺼냄
@PostMapping
public ResponseEntity<ReservationResponse> reserve(
        @AuthenticationPrincipal CustomUserDetails userDetails,  // ← 자동 주입
        @RequestBody ReservationRequest request) {
    reservationService.reserve(userDetails.getUserId(), request);
    //                         ^^^^^^^^^^^^^^^^^^^^^^^^
    //                         JWT에서 추출한 userId
}
```

### BCrypt 해싱이란?

```
원본: "password123"
해싱: "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
                └──┘ └──────────────────────────────────────────────────┘
               cost    해시값 (매번 다른 salt 적용 → 같은 비밀번호도 다른 결과)
```
- **단방향**: 해시에서 원본을 복원할 수 없습니다.
- **salt**: 매번 랜덤 값을 섞으므로 같은 비밀번호도 다른 해시가 됩니다.
- **cost factor (10)**: 해싱 반복 횟수. 높을수록 느리지만 안전합니다.

---

## 9. 예매 흐름 — 핵심 비즈니스 로직

> 파일: `service/reservation/PessimisticLockReservationService.java`

### 예매 요청 → 응답까지의 전체 흐름

```
POST /api/reservations
Body: { "scheduleId": 1, "seatIds": [3, 5] }
Header: Authorization: Bearer {jwt}
```

```java
@Transactional  // 하나의 DB 트랜잭션으로 묶음
public ReservationResponse reserve(Long userId, ReservationRequest request) {

    // ① 사용자 조회
    User user = userRepository.findById(userId).orElseThrow(...);

    // ② 스케줄 조회
    ConcertSchedule schedule = concertScheduleRepository.findById(request.scheduleId()).orElseThrow(...);

    // ③ 좌석 ID 정렬 (데드락 방지!!)
    List<Long> sortedSeatIds = request.seatIds().stream().sorted().toList();
    //  [5, 3] → [3, 5] : 항상 같은 순서로 락을 획득

    // ④ 비관적 락으로 좌석 조회 (SELECT ... FOR UPDATE)
    List<Seat> seats = seatRepository.findAllByIdInAndAvailableForUpdate(sortedSeatIds);
    // → 이 시점에서 다른 트랜잭션은 이 좌석들에 접근 불가 (대기)

    // ⑤ All-or-Nothing 검증
    if (seats.size() != sortedSeatIds.size()) {
        throw new SeatNotAvailableException("선택한 좌석 중 이미 예매된 좌석이 있습니다.");
    }
    // 2석 요청했는데 1석만 AVAILABLE → 전체 실패 (부분 예매 없음)

    // ⑥ 좌석 HOLD 처리
    seats.forEach(Seat::hold);  // AVAILABLE → HELD

    // ⑦ 총 금액 계산
    int totalAmount = seats.stream().mapToInt(Seat::getPrice).sum();

    // ⑧ 예매 생성 (5분 후 만료)
    LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);
    Reservation reservation = Reservation.create(user, schedule, totalAmount, expiresAt);
    reservationRepository.save(reservation);

    // ⑨ 예매-좌석 매핑 (중간 테이블)
    for (Seat seat : seats) {
        ReservationSeat rs = ReservationSeat.create(reservation, seat);
        reservationSeatRepository.save(rs);
    }

    // ⑩ 잔여 좌석 수 감소
    schedule.decreaseAvailableSeats(seats.size());

    return ReservationResponse.from(reservation);
}
// @Transactional 끝 → 여기서 COMMIT
// → 실패 시 자동 ROLLBACK (좌석 상태, 예매, 잔여 좌석 모두 원복)
```

### All-or-Nothing이란?

```
사용자가 좌석 3, 5를 함께 예매 요청
→ 좌석 3: AVAILABLE ✅
→ 좌석 5: HELD (다른 사람이 먼저 점유) ❌

결과: 좌석 3도 예매하지 않음. 전체 실패.
→ "2석 중 1석만 예매되는" 상황 방지
```

**왜?**: 콘서트는 보통 일행과 함께 갑니다. 4석 중 2석만 예매되면 나머지 2명은?
→ "전부 아니면 전무"가 사용자 경험 상 올바릅니다.

---

## 10. 비관적 락 — 동시성 제어의 핵심

### 문제 상황: 동시 예매

```
시간순서:
T1 (사용자 A): SELECT * FROM seats WHERE id = 3;        → status = 'AVAILABLE' ✅
T2 (사용자 B): SELECT * FROM seats WHERE id = 3;        → status = 'AVAILABLE' ✅
T1: UPDATE seats SET status = 'HELD' WHERE id = 3;      → 성공
T2: UPDATE seats SET status = 'HELD' WHERE id = 3;      → 성공?! (이미 HELD인데!)
→ 좌석 3이 2명에게 예매됨 = 데이터 정합성 깨짐!!
```

### 해결: SELECT FOR UPDATE (비관적 락)

```sql
-- SeatRepository.java의 실제 쿼리
SELECT s FROM Seat s
WHERE s.id IN :seatIds AND s.status = 'AVAILABLE'
ORDER BY s.id
FOR UPDATE     ←── 이 행들에 대해 배타적 락(Exclusive Lock) 획득
```

```
시간순서 (비관적 락 적용 후):
T1: SELECT ... FOR UPDATE WHERE id = 3;  → 락 획득, status = 'AVAILABLE'
T2: SELECT ... FOR UPDATE WHERE id = 3;  → ⏳ 대기 (T1이 락을 보유 중)
T1: UPDATE status = 'HELD'; COMMIT;      → 락 해제
T2: (락 획득) SELECT 결과: status = 'HELD' → AVAILABLE이 아님 → 빈 결과
→ seats.size() != sortedSeatIds.size() → SeatNotAvailableException
→ 정확히 1명만 성공!
```

### JPA에서 비관적 락 선언

```java
// 파일: repository/SeatRepository.java

@Lock(LockModeType.PESSIMISTIC_WRITE)          // (1) FOR UPDATE 추가
@Query("SELECT s FROM Seat s " +
       "WHERE s.id IN :seatIds " +
       "AND s.status = 'AVAILABLE' " +
       "ORDER BY s.id")                         // (2) 데드락 방지: ID 순 정렬
List<Seat> findAllByIdInAndAvailableForUpdate(@Param("seatIds") List<Long> seatIds);
```

#### (1) `@Lock(PESSIMISTIC_WRITE)`
- 이 어노테이션을 붙이면 JPA가 생성하는 SQL에 `FOR UPDATE`를 자동 추가합니다.
- `PESSIMISTIC_WRITE` = 배타적 락 (읽기/쓰기 모두 차단)
- `PESSIMISTIC_READ` = 공유 락 (읽기는 허용, 쓰기만 차단)

#### (2) `ORDER BY s.id` — 데드락 방지

```
데드락 시나리오 (정렬 없을 때):
T1: LOCK 좌석3 → LOCK 좌석5 시도 (대기)
T2: LOCK 좌석5 → LOCK 좌석3 시도 (대기)
→ 서로 상대방이 가진 락을 기다림 = 영원히 대기 = 데드락!

정렬 적용 후:
T1: LOCK 좌석3 → LOCK 좌석5 (순서대로)
T2: LOCK 좌석3 시도 → 대기 (T1이 먼저)
→ 항상 같은 순서로 락을 획득하므로 데드락 불가능
```

### 비관적 락 vs 낙관적 락 (미리보기)

| | 비관적 락 (1차 MVP) | 낙관적 락 (2차) |
|---|---|---|
| **방식** | `SELECT FOR UPDATE` | `@Version` 컬럼 |
| **시점** | 조회 시 즉시 락 | 수정 시 충돌 감지 |
| **충돌 시** | 대기 | 예외 + 재시도 |
| **장점** | 확실한 데이터 보호 | 높은 동시 처리량 |
| **단점** | 대기 시간 (성능 저하) | 재시도 로직 필요 |
| **적합** | 충돌이 많은 경우 (인기 좌석) | 충돌이 적은 경우 |

---

## 11. 결제 흐름

> 파일: `service/payment/PaymentService.java`

```java
@Transactional
public PaymentResponse pay(Long userId, PaymentRequest request) {

    // ① 예매 조회
    Reservation reservation = reservationRepository.findById(request.reservationId())
            .orElseThrow(() -> new ReservationNotFoundException("예매를 찾을 수 없습니다."));

    // ② 본인 확인
    if (!reservation.getUser().getId().equals(userId))
        throw new InvalidReservationStateException("본인의 예매만 결제할 수 있습니다.");

    // ③ 상태 확인 (PENDING만 결제 가능)
    if (reservation.getStatus() != ReservationStatus.PENDING)
        throw new InvalidReservationStateException("대기 중인 예매만 결제할 수 있습니다.");

    // ④ 만료 확인
    if (reservation.isExpired())
        throw new PaymentException("예매가 만료되었습니다.");

    // ⑤ 결제 생성 (mock PG — 실제 PG 연동은 추후)
    Payment payment = Payment.create(reservation, reservation.getTotalAmount());
    paymentRepository.save(payment);

    // ⑥ 예매 확정: PENDING → CONFIRMED
    reservation.confirm();

    // ⑦ 좌석 확정: HELD → RESERVED
    List<ReservationSeat> reservationSeats = reservationSeatRepository.findByReservationId(reservation.getId());
    for (ReservationSeat rs : reservationSeats) {
        rs.getSeat().reserve();   // HELD → RESERVED
    }

    return PaymentResponse.from(payment);
}
```

### 결제 전후 상태 변화

```
결제 전:
  Reservation: PENDING    →  결제 후: CONFIRMED
  Seat:        HELD       →  결제 후: RESERVED
  Payment:     (없음)     →  결제 후: COMPLETED

결제 실패/만료 시:
  Reservation: PENDING    →  EXPIRED (또는 CANCELLED)
  Seat:        HELD       →  AVAILABLE (반환)
  Payment:     (없음)     →  (생성 안 됨)
```

### mock PG란?
실제 결제 시스템(PG: Payment Gateway)은 토스페이먼츠, 아임포트 등 외부 서비스를 연동해야 합니다.
MVP에서는 "결제 요청 = 즉시 성공"으로 처리하고, 실제 PG 연동은 추후 구현합니다.

---

## 12. 예외 처리 설계

### 예외 계층 구조

```
RuntimeException
└── BusinessException (abstract)          ← HTTP 상태 코드 + 에러 코드 포함
    ├── UnauthorizedException (401)       ← 인증 실패
    ├── ReservationNotFoundException (404) ← 예매 없음
    ├── SeatNotAvailableException (409)   ← 좌석 이미 점유
    ├── SoldOutException (409)            ← 매진
    ├── PaymentException (400)            ← 결제 오류
    └── InvalidReservationStateException (400) ← 잘못된 예매 상태
```

### BusinessException 기본 클래스

```java
public abstract class BusinessException extends RuntimeException {
    private final HttpStatus httpStatus;  // 401, 404, 409 등
    private final String code;            // "SEAT_NOT_AVAILABLE" 등

    protected BusinessException(HttpStatus httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }
}
```

### GlobalExceptionHandler — 모든 예외를 잡아서 통일된 응답 형식으로

```java
@RestControllerAdvice  // 모든 Controller에 적용
public class GlobalExceptionHandler {

    // ① 비즈니스 예외 → 각각의 HTTP 상태 코드
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        return ResponseEntity.status(e.getHttpStatus())
                .body(ErrorResponse.of(e.getCode(), e.getMessage()));
    }
    // 예: SeatNotAvailableException → 409 Conflict
    // { "code": "SEAT_NOT_AVAILABLE", "message": "선택한 좌석 중 이미 예매된 좌석이 있습니다." }

    // ② 유효성 검증 실패 → 400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(...) { ... }
    // 예: email 빈 값 → { "code": "VALIDATION_ERROR", "message": "email: 이메일은 필수입니다." }

    // ③ 예상치 못한 예외 → 500 (에러 로그 기록)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unhandled exception", e);  // 스택트레이스 기록
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다."));
    }
    // 사용자에게는 내부 정보를 노출하지 않고, 로그에만 기록
}
```

### ErrorResponse (통일된 에러 응답)

```java
public record ErrorResponse(String code, String message, LocalDateTime timestamp) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, LocalDateTime.now());
    }
}
```

**모든 에러 응답이 같은 구조**:
```json
{
    "code": "SEAT_NOT_AVAILABLE",
    "message": "선택한 좌석 중 이미 예매된 좌석이 있습니다.",
    "timestamp": "2026-02-08T23:40:33.045"
}
```

---

## 13. 테스트 전략

### Testcontainers란?

테스트 실행 시 **실제 Docker 컨테이너**(PostgreSQL)를 자동으로 띄우고, 테스트가 끝나면 자동으로 제거합니다.

```java
// 파일: test/config/TestContainersConfig.java

@TestConfiguration
public class TestContainersConfig {

    @Bean
    @ServiceConnection  // Spring Boot가 자동으로 datasource URL을 주입
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("concert_booking_test")
                .withUsername("test")
                .withPassword("test");
    }
}
```

**`@ServiceConnection`의 마법**: 이 어노테이션 하나면 `application.yml`의 datasource 설정을
Testcontainers가 생성한 컨테이너의 접속 정보로 자동 교체합니다.

### 테스트 프로파일 설정

```yaml
# 파일: test/resources/application-test.yml

spring:
  autoconfigure:
    exclude:
      - KafkaAutoConfiguration         # Kafka 미사용
      - RedisAutoConfiguration          # Redis 미사용
      - RedisRepositoriesAutoConfiguration
```

`@ActiveProfiles("test")`를 붙이면 `application.yml` + `application-test.yml`이 합쳐집니다.
test.yml의 설정이 우선하므로 Kafka/Redis가 비활성화됩니다.

### 테스트 종류

| 테스트 | 파일 | 검증 내용 |
|--------|------|-----------|
| 컨텍스트 로드 | `ConcertBookingApplicationTest` | Spring 컨텍스트 정상 기동 |
| 인증 통합 | `AuthIntegrationTest` | 회원가입, 로그인, 비밀번호 오류, 중복 이메일 |
| 예매 E2E | `BookingFlowIntegrationTest` | 전체 흐름 + 취소 흐름 |
| 동시성 | `ConcurrencyIntegrationTest` | 10명 동시 예매 → 1명만 성공 |

---

## 14. 동시성 테스트 — 왜 1명만 성공하는가

> 파일: `test/integration/ConcurrencyIntegrationTest.java`

### 테스트 구조

```java
@Test
@DisplayName("10명이 동시에 같은 좌석 1개 예매 → 1명만 성공")
void concurrent_reservation_only_one_succeeds() throws InterruptedException {
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);  // 10개 스레드 풀
    CountDownLatch latch = new CountDownLatch(threadCount);               // 모든 스레드 완료 대기용
    AtomicInteger successCount = new AtomicInteger(0);                    // 스레드 안전한 카운터
    AtomicInteger failCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
        final Long userId = userIds.get(i);  // 각 스레드 = 다른 사용자
        executor.submit(() -> {
            try {
                ReservationRequest request = new ReservationRequest(scheduleId, List.of(targetSeatId));
                reservationService.reserve(userId, request);  // 같은 좌석 예매 시도
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                latch.countDown();  // "나 끝났어" 신호
            }
        });
    }

    latch.await();       // 10개 스레드 모두 끝날 때까지 대기
    executor.shutdown();

    assertThat(successCount.get()).isEqualTo(1);  // 정확히 1명
    assertThat(failCount.get()).isEqualTo(9);     // 나머지 9명 실패
}
```

### 실행 시간순서 (시뮬레이션)

```
시간  Thread1         Thread2         Thread3         ... Thread10
──────────────────────────────────────────────────────────────────
t0    reserve() 호출  reserve() 호출  reserve() 호출  ...
t1    SELECT FOR UPDATE → 좌석3 락 획득
t2                    SELECT FOR UPDATE → ⏳ 대기 (Thread1이 락 보유)
t3                                    SELECT FOR UPDATE → ⏳ 대기
t4    hold() → HELD
t5    COMMIT → 락 해제 ✅ 성공
t6                    SELECT FOR UPDATE → 좌석3 status='HELD'
t7                    seats.size()==0 != 1 → SeatNotAvailableException ❌
t8                                    SELECT FOR UPDATE → 좌석3 status='HELD'
t9                                    seats.size()==0 → Exception ❌
...
```

### 핵심 개념 설명

#### ExecutorService
```java
ExecutorService executor = Executors.newFixedThreadPool(10);
```
- 10개 스레드를 미리 만들어 두는 "스레드 풀"입니다.
- `executor.submit(task)`: 작업을 풀에 제출하면 남는 스레드가 실행합니다.

#### CountDownLatch
```java
CountDownLatch latch = new CountDownLatch(10);  // 카운트: 10
// 각 스레드 끝에서
latch.countDown();  // 카운트 -1
// 메인 스레드에서
latch.await();  // 카운트가 0이 될 때까지 대기
```
- "10개 작업이 모두 끝날 때까지 기다려"를 구현하는 동기화 도구입니다.

#### AtomicInteger
```java
AtomicInteger successCount = new AtomicInteger(0);
successCount.incrementAndGet();  // 스레드 안전한 증가
```
- 일반 `int`는 여러 스레드가 동시에 증가시키면 값이 꼬일 수 있습니다.
- `AtomicInteger`는 CAS(Compare-And-Swap) 연산으로 원자적 증가를 보장합니다.

#### 왜 일반 int가 위험한가?
```
int count = 0;
Thread1: 읽기(0) → +1 → 쓰기(1)
Thread2: 읽기(0) → +1 → 쓰기(1)   // Thread1의 결과를 덮어씀
→ 2번 증가시켰는데 결과는 1 (Lost Update)
```

---

## 15. Spring Boot 핵심 개념 정리

### @Transactional

```java
@Transactional
public ReservationResponse reserve(...) {
    // 이 메서드 전체가 하나의 DB 트랜잭션
    // 중간에 예외 발생 → 자동 ROLLBACK (모든 변경 취소)
    // 정상 완료 → 자동 COMMIT
}

@Transactional(readOnly = true)
public List<ConcertResponse> getConcerts() {
    // readOnly = true: SELECT만 수행하는 메서드에 사용
    // Hibernate가 변경 감지(Dirty Checking)를 건너뜀 → 성능 향상
}
```

### Dirty Checking (변경 감지)

```java
@Transactional
public void cancelReservation(...) {
    Reservation reservation = reservationRepository.findById(id).orElseThrow(...);
    reservation.cancel();  // status를 CANCELLED로 변경
    // reservationRepository.save(reservation); ← 호출하지 않아도 됨!
    // → @Transactional 끝에서 JPA가 자동으로 변경을 감지하고 UPDATE 실행
}
```

JPA의 영속성 컨텍스트(Persistence Context)가 Entity의 **원래 상태(스냅샷)**를 기억합니다.
트랜잭션 커밋 시 현재 상태와 스냅샷을 비교해서 **변경된 필드만 자동 UPDATE**합니다.

### @RequiredArgsConstructor + final 필드 = 생성자 주입

```java
@Service
@RequiredArgsConstructor  // Lombok: final 필드에 대한 생성자 자동 생성
public class PaymentService {
    private final ReservationRepository reservationRepository;  // final = 필수 의존성
    private final PaymentRepository paymentRepository;

    // Lombok이 아래 코드를 자동 생성:
    // public PaymentService(ReservationRepository reservationRepository,
    //                       PaymentRepository paymentRepository) {
    //     this.reservationRepository = reservationRepository;
    //     this.paymentRepository = paymentRepository;
    // }
}
```

**왜 생성자 주입?**
- `@Autowired` 필드 주입보다 **테스트하기 쉽고**, **불변성을 보장**합니다.
- `final` 필드는 생성 후 변경 불가 → 의존성이 중간에 바뀌는 버그 방지.

### Java Record

```java
public record LoginRequest(
    @NotBlank String email,
    @NotBlank String password
) { }

// Record가 자동으로 생성하는 것들:
// - 모든 필드의 getter (email(), password())
// - 생성자 (new LoginRequest("test@test.com", "1234"))
// - equals(), hashCode(), toString()
// - 불변 객체 (setter 없음, 필드는 final)
```

DTO에 Record를 사용하면 보일러플레이트 코드가 크게 줄어듭니다.

### open-in-view: false

```yaml
jpa:
  open-in-view: false  # 기본값은 true
```

| open-in-view | 설명 |
|:---:|------|
| `true` (기본) | Controller까지 영속성 컨텍스트 유지 → Lazy Loading이 Controller에서도 가능 |
| `false` (권장) | Service 계층에서만 영속성 컨텍스트 유지 → DB 커넥션 점유 시간 최소화 |

`false`로 설정하면 Service 밖에서 Lazy Loading을 사용할 수 없으므로,
필요한 데이터는 Service 안에서 미리 조회해야 합니다. **성능 면에서 훨씬 유리합니다.**

---

## 16. 설정 파일 해설

### application.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/concert_booking
    username: concert
    password: concert1234

  jpa:
    hibernate:
      ddl-auto: none         # Hibernate가 테이블을 자동 생성하지 않음
                              # → schema.sql로 직접 관리 (운영 환경 필수)
    properties:
      hibernate:
        format_sql: true      # SQL 로그를 보기 좋게 포맷
        default_batch_fetch_size: 100  # N+1 문제 완화 (IN 쿼리로 배치 조회)
    open-in-view: false       # DB 커넥션 점유 최소화

  sql:
    init:
      mode: always            # 매 시작마다 schema.sql 실행
                              # CREATE TABLE IF NOT EXISTS이므로 멱등성 보장

jwt:
  secret: local-dev-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm
  expiration: 3600000         # 1시간 (밀리초)
```

### ddl-auto 옵션 비교

| 값 | 동작 | 사용 시기 |
|:---:|------|------|
| `create` | 매번 DROP + CREATE | 절대 사용 X |
| `create-drop` | 시작 시 CREATE, 종료 시 DROP | 테스트용 |
| `update` | Entity 변경분 자동 반영 | 개발 초기 |
| `validate` | Entity와 DB 스키마 일치 검증 | 운영 전 검증 |
| `none` | 아무것도 안 함 | **운영 환경 (이 프로젝트)** |

---

## 17. 디자인 패턴과 설계 원칙

### 1. 전략 패턴 (Strategy Pattern)

```java
// 인터페이스 정의
public interface ReservationService {
    ReservationResponse reserve(Long userId, ReservationRequest request);
    void cancelReservation(Long userId, Long reservationId);
    // ...
}

// 구현체 1: 비관적 락 (1차 MVP)
@Service @Primary  // ← 기본 구현체
public class PessimisticLockReservationService implements ReservationService { ... }

// 구현체 2: 낙관적 락 (2차에서 추가 예정)
// @Service
// public class OptimisticLockReservationService implements ReservationService { ... }

// 구현체 3: Redis 분산 락 (3차에서 추가 예정)
// @Service
// public class DistributedLockReservationService implements ReservationService { ... }
```

**장점**: Controller와 다른 Service는 `ReservationService` 인터페이스에만 의존합니다.
락 전략을 바꿔도 Controller 코드는 **한 줄도 수정할 필요 없습니다**.

`@Primary`: 같은 인터페이스의 구현체가 여러 개일 때, 기본으로 주입할 것을 지정합니다.

### 2. 정적 팩토리 메서드 패턴

```java
// 생성자 대신 의미 있는 이름의 메서드로 객체 생성
Seat seat = Seat.create(schedule, "VIP", 1, 1, 150000);
Reservation reservation = Reservation.create(user, schedule, totalAmount, expiresAt);
Payment payment = Payment.create(reservation, amount);
```

**장점**:
- 메서드 이름에 의도가 드러남 (`create`, `from` 등)
- 생성 시 초기값 설정을 강제 (`status = AVAILABLE` 등)
- 외부에서 `new`를 쓸 수 없음 (기본 생성자가 `PROTECTED`)

### 3. DTO 변환 패턴

```java
public record ConcertResponse(Long id, String title, ...) {
    public static ConcertResponse from(Concert concert) {
        return new ConcertResponse(
            concert.getId(),
            concert.getTitle(),
            ...
        );
    }
}
```

Entity → DTO 변환 책임을 **DTO 자신에게** 둡니다.
Service에서 `ConcertResponse.from(concert)`으로 간결하게 변환합니다.

### 4. DataInitializer — 테스트 데이터 자동 생성

```java
@Component
@Profile("!test")        // test 프로파일에서는 실행 안 함
public class DataInitializer implements ApplicationRunner {

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (concertRepository.count() > 0) return;  // 멱등성: 이미 데이터 있으면 건너뜀
        // ... 콘서트 2개, 스케줄 4개, 좌석 200개 생성
    }
}
```

- `ApplicationRunner`: 애플리케이션 시작 후 자동 실행
- `@Profile("!test")`: 테스트에서는 각 테스트가 자체 데이터를 생성하므로 비활성화
- **멱등성**: 여러 번 실행해도 결과가 같음 (중복 생성 방지)

---

## 18. 자주 묻는 질문 (FAQ)

### Q1. 왜 setter를 안 쓰나요?

Setter는 **아무 곳에서나, 아무 값으로나** 상태를 바꿀 수 있어 위험합니다.
```java
seat.setStatus(SeatStatus.RESERVED);  // AVAILABLE에서 바로 RESERVED? 안 돼!
seat.reserve();                        // HELD가 아니면 예외 발생 → 안전
```

### Q2. `@Version`이 있는데 왜 비관적 락을 쓰나요?

`@Version`(낙관적 락)은 충돌이 적을 때 효율적이지만, 인기 좌석처럼 **충돌이 많은 경우**에는
대부분의 요청이 재시도를 해야 해서 오히려 비효율적입니다.
MVP에서는 확실한 비관적 락을 사용하고, 2차에서 낙관적 락과 성능을 비교합니다.

### Q3. 왜 schema.sql로 테이블을 만들고 ddl-auto는 none인가요?

`ddl-auto: update`는 편리하지만 위험합니다:
- 컬럼 삭제, 이름 변경을 감지 못함
- 인덱스를 자동 생성하지 않음
- 운영 환경에서 예측 불가능한 DDL 실행

직접 SQL을 작성하면 **정확히 어떤 DDL이 실행되는지** 알 수 있습니다.

### Q4. Testcontainers가 H2 인메모리 DB보다 좋은 이유는?

| | H2 | Testcontainers (PostgreSQL) |
|---|---|---|
| 속도 | 빠름 | 약간 느림 (컨테이너 시작) |
| 호환성 | PostgreSQL과 SQL 문법이 다름 | 실제 PostgreSQL 사용 |
| `FOR UPDATE` | 지원 안 됨 or 다르게 동작 | 완벽 지원 |
| 신뢰도 | "로컬에서 됐는데 서버에서 안 돼요" | 동일한 DB 엔진으로 테스트 |

이 프로젝트의 핵심인 **`SELECT FOR UPDATE` 비관적 락**은 H2에서 제대로 테스트할 수 없습니다.

### Q5. `@Profile("!test")`는 어떻게 작동하나요?

```java
@Profile("!test")  // "test 프로파일이 아닐 때만 이 빈을 생성해"
```
- 테스트: `@ActiveProfiles("test")` → DataInitializer가 빈으로 등록되지 않음
- 로컬 실행: 프로파일 없음 → DataInitializer가 빈으로 등록되어 실행됨

### Q6. 왜 예매할 때 `schedule.decreaseAvailableSeats()`를 호출하나요?

좌석 목록 API에서 매번 `SELECT COUNT(*) FROM seats WHERE status = 'AVAILABLE'`을 실행하면
좌석이 많을 때 느립니다. `available_seats` 필드를 미리 갱신해 두면 **O(1)**로 조회할 수 있습니다.
이것을 **역정규화(Denormalization)**라고 합니다.

### Q7. ReservationSeat (중간 테이블)은 왜 별도 Entity인가요?

JPA의 `@ManyToMany`를 쓸 수도 있지만, 중간 테이블에 **추가 필드**(가격 스냅샷 등)를
넣어야 할 수 있으므로, 명시적인 Entity로 만드는 것이 확장에 유리합니다.

---

## 부록: 전체 API 호출 예시

```bash
# 1. 회원가입
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"password123","nickname":"테스터"}'

# 2. 로그인 → JWT 토큰 발급
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"password123"}' | jq -r '.token')

# 3. 콘서트 목록 조회
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/concerts

# 4. 스케줄 조회
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/concerts/1/schedules

# 5. 좌석 조회
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/concerts/1/schedules/1/seats

# 6. 좌석 예매 (VIP 1, 2번)
curl -X POST http://localhost:8080/api/reservations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"scheduleId":1,"seatIds":[1,2]}'

# 7. 결제
curl -X POST http://localhost:8080/api/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reservationId":1}'

# 8. 내 예매 목록
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reservations/my
```

---

> 이 문서에서 다루지 않은 내용 (2차, 3차에서 구현 예정):
> - 낙관적 락 전략 (`@Version` 활용)
> - Redis 분산 락 (Redisson)
> - Redis 대기열 + SSE 실시간 알림
> - Kafka 이벤트 기반 좌석 반환
> - 만료 스케줄러 (ShedLock)
> - k6 부하 테스트 + 성능 비교
