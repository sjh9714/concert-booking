# Interview Guide

## 30초 요약

동일 좌석 경합에서 overselling을 막고, DB commit 이후 이벤트 발행 실패를 Outbox/DLT/manual replay로
복구 가능한 상태로 만든 콘서트 예매 백엔드입니다. Redis는 queue, token, stock pre-check에는 사용하지만
최종 기준 데이터는 PostgreSQL seat/reservation 상태로 둡니다.

## 예상 질문과 답변 포인트

| 질문 | 답변 포인트 |
| --- | --- |
| 왜 Redis stock을 최종 기준 데이터로 두지 않았나요? | Redis는 빠른 차단과 조회에 유리하지만 장애/TTL/중복 이벤트 후 복구 기준이 흐려질 수 있어 DB seat 상태를 최종 기준으로 둡니다. |
| 비관적/낙관적/Redis lock을 왜 모두 남겼나요? | 같은 API 계약에서 경합 패턴별 장단점을 비교하기 위해 분리했습니다. 공유 counter row가 있는 모델에서 optimistic lock 충돌이 크게 드러났습니다. |
| Outbox가 exactly-once를 보장하나요? | 아닙니다. 발행 의도를 DB에 남겨 유실 구간을 줄이고, 중복은 consumer idempotency와 상태 전이 조건으로 흡수합니다. |
| 결제/만료 race는 어디서 막나요? | reservation row lock과 도메인 상태 전이 메서드가 같은 reservation을 동시에 confirmed/expired로 만들지 않게 막습니다. |
| queue token abuse는 어떻게 검증했나요? | token 없음, 다른 사용자 token, 다른 schedule token, 만료 token branch smoke에서 unauthorized success 0 threshold를 확인했습니다. |
| 운영 장애가 나면 무엇을 먼저 보나요? | Outbox DEAD, DLT lag, Redis stock mismatch, queue token validation failure를 보고 runbook 순서대로 원인과 복구 가능성을 확인합니다. |
| 관측성은 어디까지 검증했나요? | local Prometheus scrape artifact로 target up, alert rule 로딩, Outbox query series 존재를 확인했습니다. 다만 alert firing, Grafana dashboard 운영, tracing, SLO는 아직 주장하지 않습니다. |

## 피해야 할 표현

- 운영형 대규모 예매 시스템을 완성했다고 말하지 않습니다.
- k6 D 정식 결과와 E/F 정식 부하 수치를 측정 완료처럼 말하지 않습니다.
- Outbox를 exactly-once 보장으로 설명하지 않습니다.
