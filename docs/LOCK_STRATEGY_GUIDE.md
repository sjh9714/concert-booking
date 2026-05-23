# Lock Strategy Guide

이 문서는 Concert Booking의 세 가지 예매 전략을 언제 선택할지 설명합니다. 수치는
`docs/PERF_RESULT.md`와 README에 이미 기록된 A/B/C k6 결과를 중심으로 해석합니다.
결제/만료 race 검증(Scenario D), 중복 요청 idempotency replay/conflict 검증(Scenario E),
대기열 token abuse 검증(Scenario F)은 세 전략 x 3회 formal local repeat를
branch/threshold 검증 근거로만 사용하고, 운영 성능 비교로 확장하지 않습니다.

## 요약

| 상황 | 우선 고려 전략 | 이유 |
| --- | --- | --- |
| 동일 좌석 경합이 높음 | Pessimistic Lock | 같은 좌석 row를 DB에서 직렬화해 결과가 가장 직관적입니다. |
| 소진 이후 실패 요청이 많음 | Redis Distributed Lock | Redis stock pre-check로 DB transaction 진입 전에 실패를 빠르게 반환할 수 있습니다. |
| 충돌이 낮고 공유 counter row가 없음 | Optimistic Lock | lock wait를 줄일 수 있지만 retry 정책이 필요합니다. |
| 공유 counter row가 있음 | Optimistic Lock 주의 | `ConcertSchedule.availableSeats` 같은 공유 row version conflict가 성공률을 낮출 수 있습니다. |
| Redis stock과 DB가 어긋날 수 있음 | DB 기준 reconciliation | Redis는 빠른 보조 상태이고 최종 기준 데이터는 DB입니다. |

## Pessimistic Lock

### 적합한 경우

- 동일 좌석에 요청이 몰립니다.
- overselling 방지가 처리량보다 중요합니다.
- 실패 결과보다 상태 설명 가능성이 더 중요합니다.

### 현재 근거

- 동일 좌석 100 concurrent requests에서 success 1, fail 99, overselling 0을 기록했습니다.
- 서로 다른 좌석 50명 예약에서 50/50 성공을 확인했습니다.

### 비용

- row lock 대기와 DB connection 점유가 증가합니다.
- hot row가 많아질수록 p95가 증가할 수 있습니다.

## Optimistic Lock

### 적합한 경우

- 충돌률이 낮습니다.
- 공유 counter row가 없거나, counter update를 분리할 수 있습니다.
- 재시도 정책과 사용자 경험을 함께 설계할 수 있습니다.

### 현재 해석

낙관적 락은 서로 다른 좌석 예약에서도 `ConcertSchedule.availableSeats` 공유 row version conflict로
성공률이 낮아질 수 있습니다. 이 결과는 낙관적 락이 항상 부적합하다는 뜻이 아니라, 공유 counter
row가 있는 모델에서는 충돌 비용이 쉽게 드러난다는 뜻입니다.

## Redis Distributed Lock

### 적합한 경우

- 좌석이 이미 소진된 뒤에도 쓰기 요청이 계속 들어옵니다.
- DB transaction 진입 전에 빠르게 실패를 반환하고 싶습니다.
- Redis stock mismatch를 DB 기준으로 보정할 reconciliation 경로가 있습니다.

### 현재 근거

- 서로 다른 좌석 50명 예약에서 50/50 성공을 확인했습니다.
- Mixed Load에서는 Redis stock pre-check가 소진 이후 DB 진입 비용을 줄이는 방향으로 해석됩니다.

### 비용

- Redis stock과 DB 상태가 어긋날 수 있습니다.
- Redis 장애 자동 fallback은 구현하지 않았습니다.
- 최종 기준 데이터는 DB이므로 reconciliation 절차가 필요합니다.

## 선택 질문

| 질문 | 의미 |
| --- | --- |
| 사용자가 가장 많이 경합하는 단위가 seat row인가 schedule counter인가? | 락 대상과 공유 row 충돌 가능성을 결정합니다. |
| 실패 요청을 DB 전에 차단해야 하는가? | Redis stock pre-check의 이점이 커집니다. |
| Redis가 틀렸을 때 무엇으로 복구할 것인가? | DB 최종 기준 데이터와 reconciliation이 필요합니다. |
| retry가 사용자 경험을 해치지 않는가? | 낙관적 락 적용 가능성을 판단합니다. |
| 결제/만료 race와 함께 설명해야 하는가? | reservation row lock과 상태 전이 메서드가 중요해집니다. |

## 면접에서 짧게 설명할 문장

> 동일 좌석 경합은 DB row lock으로 직렬화해 overselling을 막고, 소진 이후 반복 실패 요청은 Redis
> stock pre-check로 DB 진입 전에 차단했습니다. 다만 Redis는 최종 기준 데이터가 아니므로 DB
> `Seat.status` 기준 reconciliation을 별도로 두었습니다.
