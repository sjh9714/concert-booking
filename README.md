# Concert Booking

> 공연을 고르고, 좌석을 예약하고, 예매 내역을 확인하는 콘서트 예매 서비스

Concert Booking은 공연 목록에서 시작해 좌석 선택과 결제, 예매 확인까지 이어지는 개인 프로젝트입니다. 사용자가 선택한 좌석이 이미 예약됐다면 최신 좌석표에서 다시 고르고, 결제를 마치지 않은 예약은 취소하거나 만료 후 다시 선택할 수 있습니다.

## 어떤 서비스를 만들었나요?

예매는 '예약 성공' 응답 하나로 끝나지 않습니다. 관객은 남아 있는 좌석을 보고, 결제할 시간을 확보하고, 자신의 예매가 확정됐는지 알아야 합니다.

이 프로젝트는 이 흐름을 작은 서비스로 구현합니다. 공연·좌석·관객은 데모 데이터이며, 실제 결제나 티켓 발권 없이 예매 과정을 체험할 수 있습니다.

## 예매 흐름

1. 가입하거나 로그인한 뒤 공연과 일정을 선택합니다.
2. 좌석표에서 원하는 좌석을 고르고 예약합니다.
3. 선점 만료 시각을 확인하고 테스트 결제를 진행합니다.
4. 예매 내역에서 공연·일정·좌석·결제 상태를 확인합니다.
5. 결제 전에는 취소할 수 있습니다. 결제하지 않은 선점은 5분 후 만료됩니다.

## 화면과 주요 기능

![Concert Booking의 예매 화면](docs/assets/screens/demo-desktop.png)

실제 로컬 서비스에서 좌석을 선택하고 테스트 결제를 진행한 화면입니다.

| 기능 | 사용자가 할 수 있는 일 |
|---|---|
| 공연 찾기 | 공연 목록과 상세 정보에서 일정·좌석을 확인합니다. |
| 좌석 선택 | 좌석표에서 선택 가능한 좌석을 고릅니다. 이미 선택된 좌석은 다른 좌석으로 변경합니다. |
| 테스트 결제 | 선점한 예약의 정보를 확인하고 예매를 확정합니다. 실제 카드 정보나 결제 금액은 수집하지 않습니다. |
| 내 예매 | 자신의 예약·결제 상태를 확인하고 미결제 예약을 취소합니다. |
| 좌석 반환 | 취소되거나 만료된 미결제 좌석을 다시 예매할 수 있습니다. |

<details>
<summary>모바일 예매 화면</summary>

<img src="docs/assets/screens/demo-mobile.png" alt="Concert Booking 모바일 예매 화면" width="320" />

</details>

## 현재 범위

공연 선택부터 테스트 결제·예매 확인, 결제 전 취소·만료까지 제공합니다. 실제 PG 결제, 결제 후 환불, 티켓 발권은 포함하지 않습니다. 기본 실행에서는 대기열을 거치지 않고 좌석을 선택합니다.

이전의 대기열과 여러 동시성 제어 방식은 별도 실험 자료로 남아 있습니다. 기본 서비스를 체험하기 위한 필수 절차는 아닙니다.

## 로컬에서 실행하기

Python 3와 Docker가 필요합니다.

```bash
python3 scripts/init-service-env.py
docker compose --env-file .env.service -p concert-rebuild -f compose.service.yml up -d --build
```

`http://localhost:4176`에서 가입 후 예매할 수 있습니다. API는 `http://localhost:18082`입니다. 초기화 스크립트는 로컬 설정을 생성하며, 이미 있는 `.env.service`를 덮어쓰지 않습니다. 이 파일은 Git에 올리지 않습니다.

```bash
# 종료하되 로컬 DB 데이터 보존
docker compose --env-file .env.service -p concert-rebuild -f compose.service.yml stop
```

## 사용 기술

| 영역 | 기술 |
|---|---|
| 웹 | React · TypeScript · Vite |
| API | Java 21 · Spring Boot · Spring MVC · JPA |
| 데이터·실행 | PostgreSQL · Docker Compose |

## 개발 자료

[서비스 구조와 실행·테스트](docs/SERVICE_GUIDE.md) · [검증 기록](docs/VERIFICATION.md) · [설계 문서](docs/ARCHITECTURE.md) · [화면 이미지 출처](docs/assets/README.md)

개인 프로젝트로 AI 지원 구현과 로컬 검증을 진행했습니다. 기술 선택과 실험의 상세 내용은 개발 문서에 분리해 두었습니다.
