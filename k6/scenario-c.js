/**
 * Scenario C: Mixed Load (실제 트래픽 시뮬레이션)
 *
 * 200 VU: 70% 좌석 조회 + 30% 예매.
 * 예매 대상은 80% 확률로 인기 좌석(상위 20%)을 선택 → Hot spot 발생.
 * 실제 트래픽 패턴에서의 응답 시간과 에러율을 측정.
 */
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { CONCERT_ID, SCHEDULE_ID } from './config.js';
import { getSeats, reserve, resetData, setupUsers } from './helpers.js';

const readSuccess = new Counter('read_success');
const writeSuccess = new Counter('write_success');
const writeFail = new Counter('write_fail');
const readDuration = new Trend('read_duration', true);
const writeDuration = new Trend('write_duration', true);

const MIXED_VUS = parseInt(__ENV.MIXED_VUS || '200');

export const options = {
    scenarios: {
        mixed_load: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: MIXED_VUS },
                { duration: '30s', target: MIXED_VUS },
                { duration: '5s', target: 0 },
            ],
        },
    },
    thresholds: {
        read_duration: ['p(95)<2000'],   // 읽기 p95 < 2초
        write_duration: ['p(95)<5000'],  // 쓰기 p95 < 5초
    },
};

export function setup() {
    // 데이터 리셋
    resetData(SCHEDULE_ID);

    // 사용자 생성
    const users = setupUsers(MIXED_VUS, `scenario-c-${Date.now()}`);

    // 좌석 목록 확보
    const seatsRes = getSeats(users[0].token, CONCERT_ID, SCHEDULE_ID);
    const seats = JSON.parse(seatsRes.body);
    const availableSeats = seats.filter(s => s.status === 'AVAILABLE');
    const seatIds = availableSeats.map(s => s.id);

    // 인기 좌석 (상위 20%)
    const hotSeatCount = Math.max(1, Math.floor(seatIds.length * 0.2));
    const hotSeats = seatIds.slice(0, hotSeatCount);
    const coldSeats = seatIds.slice(hotSeatCount);

    console.log(`[Setup] 사용자 ${users.length}명, 전체 좌석 ${seatIds.length}개`);
    console.log(`- 인기 좌석: ${hotSeats.length}개, 일반 좌석: ${coldSeats.length}개`);

    return { users, hotSeats, coldSeats, allSeatIds: seatIds };
}

export default function (data) {
    const vuIndex = (__VU - 1) % data.users.length;
    const user = data.users[vuIndex];
    if (!user) return;

    // 70% 읽기, 30% 쓰기
    if (Math.random() < 0.7) {
        // 좌석 조회
        const start = Date.now();
        const res = getSeats(user.token, CONCERT_ID, SCHEDULE_ID);
        readDuration.add(Date.now() - start);

        if (check(res, { 'seats fetched': (r) => r.status === 200 })) {
            readSuccess.add(1);
        }
    } else {
        // 예매 — 80% 확률로 인기 좌석 선택
        let targetSeatId;
        if (Math.random() < 0.8 && data.hotSeats.length > 0) {
            targetSeatId = data.hotSeats[Math.floor(Math.random() * data.hotSeats.length)];
        } else if (data.coldSeats.length > 0) {
            targetSeatId = data.coldSeats[Math.floor(Math.random() * data.coldSeats.length)];
        } else {
            targetSeatId = data.allSeatIds[Math.floor(Math.random() * data.allSeatIds.length)];
        }

        const start = Date.now();
        const res = reserve(user.token, SCHEDULE_ID, [targetSeatId]);
        writeDuration.add(Date.now() - start);

        if (res.status === 201) {
            writeSuccess.add(1);
        } else {
            writeFail.add(1);
        }
    }

    sleep(0.1);  // 실제 사용자 행동 시뮬레이션
}

export function teardown(data) {
    console.log(`[Teardown] Scenario C 완료`);
    console.log(`- VU 수: ${MIXED_VUS}`);
    console.log('- 결과: k6 메트릭에서 읽기/쓰기 RPS, p95, 에러율 확인');
}
