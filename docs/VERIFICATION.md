# 검증 기록: 2026-10-02

## 재현

Java 21과 실행 중인 Docker가 필요합니다. 통합 테스트는 전용 Testcontainers DB를 사용합니다.

```sh
./gradlew test
./gradlew test --tests '*ServiceBookingIntegrationTest'
cd web
npm ci
npm run test:run
npm run lint
npm run build
npx playwright test -c playwright.service.config.ts
```

화면 검증 전 README의 `compose.service.yml`을 실행합니다. 기존 대기열 실험용 `playwright.config.ts`는 별도 환경용입니다.

## 확인 결과

- A 취소 → 좌석 반환 → B 재예약 → A 이벤트 재처리는 수정 전 `AVAILABLE`로 잘못 반환되어 실패했습니다. 소유권 검사 후 `HELD`와 B의 소유 예약 ID를 유지합니다.
- 새 기본 모드 7개: 토큰 없는 선점, DB 좌석 기반 잔여석, 즉시 취소·반환, 여러 좌석 롤백, 예약·결제 재처리, 만료·결제 경쟁, 같은/다른 좌석 동시 요청. PostgreSQL만 사용하는 구성으로 통과했습니다.
- 기존 테스트 포함 전체 **106개 통과, 건너뜀 0**. 첫 전체 실행의 DLT 테스트는 실제 취소 없이 취소 이벤트를 만드는 fixture를 수정했습니다. 취소 상태 선행 조건을 맞추고 기존 재처리 검증은 유지했습니다.
- 전체 재실행 중 Kafka 컨테이너 시작 시간 초과로 실행이 실패한 적이 있습니다. 이미지 빌드가 끝난 뒤 재실행하여 전체 통과를 확인했습니다.
- 프론트 단위 14개, lint·TypeScript·빌드 통과. 실제 API 기반 데스크톱·모바일 E2E 4개 통과: 가입→공연→선점→테스트 결제, 취소→같은 좌석 재예약. 기본 모드에서 `/api/queue/` 호출 0. 결제 확정 화면의 axe serious/critical 0, 화면 직접 확인.

## 동시 요청 기록

[원표본과 조건](evidence/2026-10-02/service-holds/summary.json): 같은 좌석 8개 중 성공 1/거절 7, 서로 다른 좌석 8개 중 성공 8/거절 0. 워밍업 없이 조건별 1회, Java 서비스 호출 시간(트랜잭션 포함)입니다. HTTP 부하 시험·전략 전후 개선율이 아닙니다. 원표본 CSV와 기준 커밋·미커밋 변경을 같이 보관합니다.

새 기본 모드의 대규모 처리량·운영 장애·실제 결제는 미검증입니다. 기존 수치는 [과거 성능 문서](PERF_RESULT.md)의 조건 안에서만 해석합니다.

## 공개 전 실행 설정

로컬 고정 DB 비밀번호 대신 `scripts/init-service-env.py`가 `.env.service`에 비밀번호와 서명 키를 생성합니다. 이 파일은 Git에서 제외하고 기존 파일은 덮어쓰지 않습니다. Compose는 `--env-file .env.service`를 명시하며, 값이 없으면 실행 전 실패합니다. 기존 DB와 다른 비밀번호로 다시 시작하지 않습니다.
