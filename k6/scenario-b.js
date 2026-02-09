/**
 * Scenario B: Distributed Seat Reservation (처리량 측정)
 *
 * 100명이 각각 다른 좌석을 예매 → 모두 성공해야 함.
 * 경합 없는 환경에서의 최대 처리량(RPS)과 응답 시간을 측정.
 */
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { CONCERT_ID, SCHEDULE_ID, VUS } from './config.js';
import { getSeats, reserve, resetData, setupUsers } from './helpers.js';

const successCount = new Counter('reservation_success');
const failCount = new Counter('reservation_fail');
const reserveDuration = new Trend('reserve_duration', true);

export const options = {
    scenarios: {
        distributed: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '60s',
        },
    },
    thresholds: {
        reserve_duration: ['p(95)<5000'],  // p95 < 5초
    },
};

export function setup() {
    // 1. 데이터 리셋
    resetData(SCHEDULE_ID);

    // 2. 사용자 생성
    const users = setupUsers(VUS, `scenario-b-${Date.now()}`);

    // 3. AVAILABLE 좌석 목록 확보
    const seatsRes = getSeats(users[0].token, CONCERT_ID, SCHEDULE_ID);
    const seats = JSON.parse(seatsRes.body);
    const availableSeats = seats.filter(s => s.status === 'AVAILABLE');

    // VU 수만큼 좌석 ID 배열 생성
    const seatIds = availableSeats.slice(0, VUS).map(s => s.id);

    console.log(`[Setup] 사용자 ${users.length}명, 좌석 ${seatIds.length}개 확보`);

    return { users, seatIds };
}

export default function (data) {
    const vuIndex = __VU - 1;
    const user = data.users[vuIndex];
    const seatId = data.seatIds[vuIndex];

    if (!user || !seatId) return;

    const start = Date.now();
    const res = reserve(user.token, SCHEDULE_ID, [seatId]);
    reserveDuration.add(Date.now() - start);

    if (res.status === 201) {
        successCount.add(1);
        check(res, { 'reservation created': (r) => r.status === 201 });
    } else {
        failCount.add(1);
        console.log(`[VU ${__VU}] 예매 실패: ${res.status} - ${res.body}`);
    }
}

export function teardown(data) {
    console.log(`[Teardown] Scenario B 완료`);
    console.log(`- VU 수: ${VUS}`);
    console.log(`- 좌석 수: ${data.seatIds.length}`);
    console.log('- 결과: k6 메트릭에서 RPS, p50/p95/p99, 성공률 확인');
}
