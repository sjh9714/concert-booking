/**
 * Scenario A: Hot Seat Contention (정합성 검증)
 *
 * 100명이 동시에 같은 좌석 1개를 예매 → 정확히 1명만 성공해야 함.
 * overselling = 0건을 검증하는 정합성 테스트.
 */
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { SCHEDULE_ID, VUS } from './config.js';
import { getSeats, reserve, resetLoadTestData, setupUsers, summary } from './helpers.js';

const successCount = new Counter('reservation_success');
const failCount = new Counter('reservation_fail');

export const options = {
    scenarios: {
        hot_seat: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '60s',
        },
    },
    thresholds: {
        reservation_success: ['count<=1'],  // 최대 1명만 성공
    },
};

export function setup() {
    // 1. 부하 테스트 fixture 리셋
    const fixture = resetLoadTestData(SCHEDULE_ID, VUS);

    // 2. reset endpoint가 보장한 deterministic 사용자 로그인
    const users = setupUsers(VUS);

    // 3. 좌석 1개 ID 확보 (첫 번째 유저의 토큰으로 조회)
    const seatsRes = getSeats(users[0].token, fixture.concertId, SCHEDULE_ID);
    const seats = JSON.parse(seatsRes.body);
    const targetSeatId = seats.find(s => s.status === 'AVAILABLE').id;

    console.log(`[Setup] 사용자 ${users.length}명 생성, 타겟 좌석 ID: ${targetSeatId}`);

    return { users, targetSeatId, concertId: fixture.concertId };
}

export default function (data) {
    const user = data.users[__VU - 1];
    if (!user) return;

    const res = reserve(user.token, SCHEDULE_ID, [data.targetSeatId]);

    if (res.status === 201) {
        successCount.add(1);
        check(res, { 'reservation created': (r) => r.status === 201 });
    } else {
        failCount.add(1);
    }
}

export function teardown(data) {
    const finalSummary = summary(SCHEDULE_ID);
    console.log(`[Teardown] Scenario A 완료`);
    console.log(`- VU 수: ${VUS}`);
    console.log(`- 타겟 좌석 ID: ${data.targetSeatId}`);
    console.log(`- 최종 예약 수: ${finalSummary.reservationCount}, Redis stock: ${finalSummary.redisStock}`);
    console.log('- 결과: k6 메트릭에서 reservation_success ≤ 1 확인');
}
