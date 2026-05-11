/**
 * Scenario D: Payment Expiration Race
 *
 * PENDING reservation을 미리 만든 뒤, VU 쌍 단위로 한쪽은 결제, 다른 한쪽은
 * load-test expire utility를 호출한다. 실제 scheduler tick을 기다리지 않고
 * 상태 전이 race를 재현하기 위한 로컬 검증용 시나리오다.
 */
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { SCHEDULE_ID } from './config.js';
import {
    expireReservation,
    getSeats,
    pay,
    reserve,
    resetLoadTestData,
    setupUsers,
    summary,
} from './helpers.js';

const paymentSuccess = new Counter('payment_success');
const expireSuccess = new Counter('expire_success');
const invalidStateCount = new Counter('invalid_state_count');

const RACE_VUS = parseInt(__ENV.VUS || '20');
const RACE_PAIRS = Math.min(parseInt(__ENV.RACE_PAIRS || `${Math.floor(RACE_VUS / 2)}`), 50);
const EFFECTIVE_VUS = Math.max(2, RACE_PAIRS * 2);

export const options = {
    scenarios: {
        payment_expiration_race: {
            executor: 'per-vu-iterations',
            vus: EFFECTIVE_VUS,
            iterations: 1,
            maxDuration: '90s',
        },
    },
};

export function setup() {
    const fixture = resetLoadTestData(SCHEDULE_ID, RACE_PAIRS);
    const users = setupUsers(RACE_PAIRS);
    const seatsRes = getSeats(users[0].token, fixture.concertId, SCHEDULE_ID);
    const seats = JSON.parse(seatsRes.body).filter(s => s.status === 'AVAILABLE');

    const reservations = [];
    for (let i = 0; i < RACE_PAIRS; i++) {
        const user = users[i];
        const seat = seats[i];
        const res = reserve(user.token, SCHEDULE_ID, [seat.id], `race-reservation-${i}`);
        check(res, { 'race pending reservation created': (r) => r.status === 201 });
        if (res.status === 201) {
            const body = JSON.parse(res.body);
            reservations.push({ reservationId: body.id, user });
        }
    }

    if (reservations.length === 0) {
        throw new Error('Scenario D setup failed: no pending reservations created');
    }

    console.log(`[Setup] Scenario D pending reservations: ${reservations.length}`);
    return { reservations };
}

export default function (data) {
    const pairIndex = Math.floor((__VU - 1) / 2) % data.reservations.length;
    const target = data.reservations[pairIndex];

    if ((__VU - 1) % 2 === 0) {
        const res = pay(target.user.token, target.reservationId, `race-payment-${target.reservationId}`);
        if (res.status === 201) {
            paymentSuccess.add(1);
        } else {
            invalidStateCount.add(1);
        }
    } else {
        const res = expireReservation(target.reservationId);
        if (res.status === 200 && JSON.parse(res.body).expired === true) {
            expireSuccess.add(1);
        } else {
            invalidStateCount.add(1);
        }
    }
}

export function teardown() {
    const finalSummary = summary(SCHEDULE_ID);
    console.log('[Teardown] Scenario D 완료');
    console.log(`- reservations: ${finalSummary.reservationCount}`);
    console.log(`- payments: ${finalSummary.paymentCount}`);
    console.log(`- duplicate payments: ${finalSummary.duplicatePaymentCount}`);
    console.log(`- Redis stock: ${finalSummary.redisStock}`);
}
