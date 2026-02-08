# Concert Booking

**"1만 명이 동시에 1,000석을 예매할 때 정합성을 어떻게 보장할 것인가"**

콘서트 좌석 예매 시스템을 통해 동시성 제어를 깊게 탐구하는 백엔드 프로젝트입니다.
비관적 락 → 낙관적 락 → Redis 분산 락 3가지 전략을 단계적으로 구현하고,
k6 부하 테스트로 각 전략의 성능과 정합성을 정량 비교합니다.

## 핵심 기술 챌린지

| 챌린지 | 해결 방법 |
|--------|-----------|
| 동시 예매 정합성 | 비관적 락 / 낙관적 락 / Redis 분산 락 3가지 전략 비교, overselling 0건 |
| 대기열 시스템 | Redis Sorted Set + SSE로 1만 명 순차 입장 |
| 좌석 임시 점유 | Redis TTL 5분 + 스케줄러 자동 해제 |
| 분산 환경 동시성 | Redisson 분산 락으로 서버 2대 크로스 정합성 |
| 성능 측정 | k6로 동일 시나리오 3회 측정, 전략별 RPS/p99 비교 |

## 기술 스택

- **Runtime**: Java 21, Spring Boot 3.4.x
- **DB**: PostgreSQL 16
- **Cache / Lock**: Redis 7 + Redisson
- **Messaging**: Apache Kafka (KRaft)
- **Real-time**: SSE (Server-Sent Events)
- **Test**: Testcontainers, k6
- **Monitoring**: Prometheus, Grafana
- **Infra**: Docker Compose

## 아키텍처

```
Client (Browser)
    │ REST API + SSE
    ▼
┌─────────────────────────────┐
│   Spring Boot Cluster       │
│   App-1 (:8081)             │
│   App-2 (:8082)             │
│       Redisson 분산 락       │
└──────────┬──────────────────┘
           │
    ┌──────▼──────┐
    │    Redis    │  분산 락 · 대기열 · 좌석 점유
    └──────┬──────┘
    ┌──────▼──────┐
    │ PostgreSQL  │  비관적/낙관적 락 · 데이터 저장
    └──────┬──────┘
    ┌──────▼──────┐
    │   Kafka     │  예매 완료/취소 이벤트 · DLT
    └─────────────┘
```

## 동시성 제어 전략

### 전략 1: 비관적 락 (Pessimistic Lock)

`SELECT ... FOR UPDATE`로 좌석에 배타적 잠금. 구현이 단순하고 정합성이 확실하지만 잠금 대기로 처리량이 저하됩니다.

### 전략 2: 낙관적 락 (Optimistic Lock)

`@Version` 기반 충돌 감지 + 재시도. 잠금 대기 없이 처리량이 높지만 경합이 심하면 재시도가 폭발합니다.

### 전략 3: Redis 분산 락 (Redisson)

Redis 재고 선검증(atomic decrement) → 좌석별 분산 락 → DB 상태 변경. DB 부하를 최소화하고 분산 환경을 지원합니다.

### 전략 비교 (k6 측정 예정)

| 메트릭 | 비관적 락 | 낙관적 락 | Redis 분산 락 |
|--------|-----------|-----------|--------------|
| RPS | 측정 예정 | 측정 예정 | 측정 예정 |
| p99 응답시간 | 측정 예정 | 측정 예정 | 측정 예정 |
| overselling | 0건 | 0건 | 0건 |

## 실행 방법

```bash
# 인프라 실행
docker compose up -d

# 애플리케이션 빌드 및 실행
./gradlew bootRun

# 테스트 (Testcontainers로 PostgreSQL, Redis, Kafka 자동 구동)
./gradlew test
```

## API

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/auth/signup` | 회원가입 |
| POST | `/api/auth/login` | 로그인 (JWT 발급) |
| GET | `/api/concerts` | 콘서트 목록 |
| GET | `/api/concerts/{id}/schedules/{scheduleId}/seats` | 좌석 현황 |
| POST | `/api/queue/enter` | 대기열 진입 |
| GET | `/api/queue/events` | SSE 실시간 순번 스트림 |
| POST | `/api/reservations` | 좌석 예매 (입장 토큰 필요) |
| POST | `/api/payments` | 결제 요청 (예매 확정) |

전체 API 목록은 [설계 문서](docs/DESIGN.md)를 참고하세요.

## 문서

- [설계 문서](docs/DESIGN.md) — ERD, API, 동시성 전략, 대기열, Kafka, ADR
- [학습 가이드](docs/STUDY_GUIDE.md) — 프로젝트 전체 코드 해설 (비관적 락, JWT, 상태 머신, 동시성 테스트 등)
- [성능 측정 결과](docs/PERF_RESULT.md) — 3가지 락 전략 비교 (구현 후 작성)
