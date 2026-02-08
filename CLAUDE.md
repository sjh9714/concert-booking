# CLAUDE.md

## 프로젝트 개요

콘서트 좌석 예매 시스템 — 동시성 제어 Deep Dive (백엔드 포트폴리오)

## 기술 스택

- Java 21, Spring Boot 3.4.x
- PostgreSQL 16 (비관적/낙관적 락)
- Redis 7 + Redisson (분산 락, 대기열, 좌석 점유)
- SSE (대기열 실시간 알림)
- Apache Kafka (KRaft 모드, 예매 이벤트)
- Docker Compose
- ShedLock (스케줄러 중복 실행 방지)
- Testcontainers, k6 (통합 테스트, 부하 테스트)

## 설계 문서

- 설계 문서: `docs/DESIGN.md`
- 학습 가이드: `docs/STUDY_GUIDE.md`
- 성능 측정 결과: `docs/PERF_RESULT.md` (3차 구현 후 작성)

## 패키지 구조

```
src/main/java/com/concert/booking/
├── config/          # Redis, Kafka, Security, Scheduler 설정
├── controller/      # REST API + SSE
├── service/
│   ├── reservation/ # 예매 서비스 (3가지 락 전략)
│   ├── queue/       # 대기열 서비스
│   ├── payment/     # 결제 서비스
│   └── concert/     # 콘서트/스케줄/좌석 서비스
├── consumer/        # Kafka Consumer
├── domain/          # Entity
├── repository/      # JPA Repository
├── dto/             # 요청/응답 DTO
├── event/           # Kafka 이벤트 스키마
└── common/          # JWT, 예외 처리, 분산 락 유틸
```

## 구현 현황

- 1차 MVP (비관적 락): 완료 — Entity, JWT 인증, 콘서트 CRUD, 예매(SELECT FOR UPDATE), 결제, 통합 테스트
- 2차 (낙관적 락): 미구현
- 3차 (Redis 분산 락 + 대기열 + Kafka): 미구현

## 빌드 / 실행

```bash
# 인프라 실행
docker compose up -d

# 애플리케이션 빌드 및 실행
./gradlew bootRun

# 테스트 (Testcontainers로 PostgreSQL 자동 구동)
./gradlew test
```

## API 엔드포인트

### 인증
- `POST /api/auth/signup` — 회원가입
- `POST /api/auth/login` — 로그인

### 콘서트
- `GET /api/concerts` — 콘서트 목록
- `GET /api/concerts/{id}` — 콘서트 상세
- `GET /api/concerts/{id}/schedules` — 스케줄 목록
- `GET /api/concerts/{id}/schedules/{scheduleId}/seats` — 좌석 현황

### 대기열
- `POST /api/queue/enter` — 대기열 진입
- `GET /api/queue/position` — 대기 순번 조회
- `GET /api/queue/events` — SSE 실시간 순번 스트림
- `GET /api/queue/token` — 입장 토큰 발급

### 예매
- `POST /api/reservations` — 좌석 예매 (입장 토큰 필요)
- `GET /api/reservations/{id}` — 예매 상세
- `GET /api/reservations/my` — 내 예매 목록
- `DELETE /api/reservations/{id}` — 예매 취소

### 결제
- `POST /api/payments` — 결제 요청
- `GET /api/payments/{id}` — 결제 상세

## 핵심 설계 결정

- 다좌석 예매: All-or-Nothing (최대 4석, 부분 실패 허용 안 함)
- 대기열: Redis ZADD NX (중복 진입 방지) + SSE 실시간 순번
- 좌석 임시 점유: Redis TTL 5분 + 스케줄러(ShedLock) → Kafka 이벤트 → Consumer 좌석 반환
- 만료/취소 흐름: 스케줄러가 상태만 EXPIRED 변경 → Kafka `reservation.cancelled` 발행 → `seat-release` Consumer가 좌석 반환
- Redis 장애 시: 분산 락 → DB 비관적 락 fallback, 대기열 bypass, DB expires_at + 스케줄러가 최종 보장

## 코딩 컨벤션

- 언어: 한국어 주석, 영어 코드
- 네이밍: 클래스 PascalCase, 메서드/변수 camelCase, 상수 UPPER_SNAKE_CASE
- DTO: 요청 `*Request`, 응답 `*Response`
- Entity: Lombok 사용 최소화 (`@Getter`, `@NoArgsConstructor(access = PROTECTED)` 정도만)
- 테스트: `*Test` (단위), `*IntegrationTest` (통합)
- 예외: 도메인별 커스텀 예외 (`SoldOutException`, `QueueNotReadyException` 등)
