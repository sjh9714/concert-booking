/**
 * Scenario E: Duplicate Request / Idempotency
 *
 * 같은 사용자가 같은 reservation Idempotency-Key로 예매를 반복하고,
 * 같은 payment Idempotency-Key로 결제를 반복한다. 네트워크 timeout 이후
 * 재요청/더블클릭이 중복 reservation/payment로 이어지는지 확인한다.
 */
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { SCHEDULE_ID } from './config.js';
import {
    directReserve,
    getSeats,
    issueQueueToken,
    pay,
    resetLoadTestData,
    setupUsers,
    summary,
} from './helpers.js';

const duplicateReservationResponse = new Counter('duplicate_reservation_response');
const duplicatePaymentResponse = new Counter('duplicate_payment_response');
const conflictCount = new Counter('conflict_count');
const requestFail = new Counter('request_fail');

const DUPLICATE_VUS = parseInt(__ENV.VUS || '20');

export const options = {
    scenarios: {
        duplicate_requests: {
            executor: 'per-vu-iterations',
            vus: DUPLICATE_VUS,
            iterations: 1,
            maxDuration: '90s',
        },
    },
};

export function setup() {
    const fixture = resetLoadTestData(SCHEDULE_ID, 1);
    const users = setupUsers(1);
    const user = users[0];
    const seatsRes = getSeats(user.token, fixture.concertId, SCHEDULE_ID);
    const seat = JSON.parse(seatsRes.body).find(s => s.status === 'AVAILABLE');
    const queueToken = issueQueueToken(user.token, SCHEDULE_ID);

    if (!queueToken || !seat) {
        throw new Error('Scenario E setup failed: no queue token or available seat');
    }

    return {
        user,
        seatId: seat.id,
        queueToken,
        reservationKey: `duplicate-reservation-${Date.now()}`,
        paymentKey: `duplicate-payment-${Date.now()}`,
    };
}

export default function (data) {
    const reservationRes = directReserve(
        data.user.token,
        SCHEDULE_ID,
        [data.seatId],
        data.queueToken,
        data.reservationKey
    );

    if (reservationRes.status === 201) {
        duplicateReservationResponse.add(1);
        const reservation = JSON.parse(reservationRes.body);
        const paymentRes = pay(data.user.token, reservation.id, data.paymentKey);
        if (paymentRes.status === 201) {
            duplicatePaymentResponse.add(1);
        } else if (paymentRes.status === 409) {
            conflictCount.add(1);
        } else {
            requestFail.add(1);
        }
    } else if (reservationRes.status === 409) {
        conflictCount.add(1);
    } else {
        requestFail.add(1);
    }

    check(reservationRes, {
        'reservation replay returns created response': (r) => r.status === 201,
    });
}

export function teardown() {
    const finalSummary = summary(SCHEDULE_ID);
    console.log('[Teardown] Scenario E 완료');
    console.log(`- reservations: ${finalSummary.reservationCount}`);
    console.log(`- payments: ${finalSummary.paymentCount}`);
    console.log(`- duplicate seat reservation suspect: ${finalSummary.duplicateSeatReservationCount}`);
    console.log(`- duplicate payment suspect: ${finalSummary.duplicatePaymentCount}`);
}
