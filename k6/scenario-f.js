/**
 * Scenario F: Queue Token Abuse
 *
 * token 없음, 다른 사용자 token, 다른 schedule token, 만료 token, 정상 token 요청을 분리해
 * 입장 토큰 우회 성공이 0건인지 확인한다.
 */
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { OTHER_SCHEDULE_ID, SCHEDULE_ID } from './config.js';
import {
    directReserve,
    expireQueueToken,
    getSeats,
    issueQueueToken,
    reserve,
    resetLoadTestData,
    setupUsers,
    summary,
} from './helpers.js';

const unauthorizedSuccessCount = new Counter('unauthorized_success_count');
const unauthorizedRejectCount = new Counter('unauthorized_reject_count');
const unexpectedRejectStatusCount = new Counter('unexpected_reject_status_count');
const normalSuccessCount = new Counter('normal_success_count');
const expectedRejectStatuses = [400, 401, 403];

const ABUSE_VUS = parseInt(__ENV.VUS || '5');
const ABUSE_ITERATIONS = parseInt(__ENV.ITERATIONS || '1');

export const options = {
    scenarios: {
        queue_token_abuse: {
            executor: 'per-vu-iterations',
            vus: ABUSE_VUS,
            iterations: ABUSE_ITERATIONS,
            maxDuration: '60s',
        },
    },
    thresholds: {
        unauthorized_success_count: ['count==0'],
        unexpected_reject_status_count: ['count==0'],
    },
};

export function setup() {
    if (OTHER_SCHEDULE_ID === SCHEDULE_ID) {
        throw new Error('Scenario F setup failed: OTHER_SCHEDULE_ID must differ from SCHEDULE_ID');
    }

    const fixture = resetLoadTestData(SCHEDULE_ID, 4);
    resetLoadTestData(OTHER_SCHEDULE_ID, 4);
    const users = setupUsers(4);
    const seatsRes = getSeats(users[0].token, fixture.concertId, SCHEDULE_ID);
    const seats = JSON.parse(seatsRes.body).filter(s => s.status === 'AVAILABLE');

    const otherUserToken = issueQueueToken(users[1].token, SCHEDULE_ID);
    const otherScheduleToken = issueQueueToken(users[0].token, OTHER_SCHEDULE_ID);
    const expiredToken = issueQueueToken(users[2].token, SCHEDULE_ID);
    expireQueueToken(users[2].userId, SCHEDULE_ID);

    if (!otherUserToken || !otherScheduleToken || !expiredToken || seats.length < 5) {
        throw new Error('Scenario F setup failed: no token or enough available seats');
    }

    return {
        users,
        seatIds: seats.slice(0, 5).map(s => s.id),
        otherUserToken,
        otherScheduleToken,
        expiredToken,
    };
}

export default function (data) {
    const mode = (__VU + __ITER) % 5;
    let res;

    if (mode === 0) {
        res = directReserve(
            data.users[0].token,
            SCHEDULE_ID,
            [data.seatIds[0]],
            undefined,
            `abuse-missing-token-${__VU}-${__ITER}`
        );
        recordUnauthorizedResult(res, 'missing token rejected');
    } else if (mode === 1) {
        res = directReserve(
            data.users[0].token,
            SCHEDULE_ID,
            [data.seatIds[1]],
            data.otherUserToken,
            `abuse-other-user-token-${__VU}-${__ITER}`
        );
        recordUnauthorizedResult(res, 'other user token rejected');
    } else if (mode === 2) {
        res = directReserve(
            data.users[0].token,
            SCHEDULE_ID,
            [data.seatIds[2]],
            data.otherScheduleToken,
            `abuse-other-schedule-token-${__VU}-${__ITER}`
        );
        recordUnauthorizedResult(res, 'other schedule token rejected');
    } else if (mode === 3) {
        res = directReserve(
            data.users[2].token,
            SCHEDULE_ID,
            [data.seatIds[3]],
            data.expiredToken,
            `abuse-expired-token-${__VU}-${__ITER}`
        );
        recordUnauthorizedResult(res, 'expired token rejected');
    } else {
        res = reserve(
            data.users[3].token,
            SCHEDULE_ID,
            [data.seatIds[4]],
            `normal-token-${__VU}-${__ITER}`
        );
        if (res.status === 201) {
            normalSuccessCount.add(1);
        }
        check(res, { 'normal token request accepted or seat conflict after first success': (r) => r.status === 201 || r.status === 400 || r.status === 409 });
    }
}

function recordUnauthorizedResult(res, label) {
    if (res.status === 201) {
        unauthorizedSuccessCount.add(1);
    } else if (expectedRejectStatuses.includes(res.status)) {
        unauthorizedRejectCount.add(1);
    } else {
        unexpectedRejectStatusCount.add(1);
    }
    check(res, { [label]: (r) => expectedRejectStatuses.includes(r.status) });
}

export function teardown() {
    const finalSummary = summary(SCHEDULE_ID);
    console.log('[Teardown] Scenario F 완료');
    console.log(`- unauthorized success count와 unexpected reject status count는 threshold로 0 검증`);
    console.log(`- reservations: ${finalSummary.reservationCount}`);
    console.log(`- Redis stock: ${finalSummary.redisStock}`);
}
