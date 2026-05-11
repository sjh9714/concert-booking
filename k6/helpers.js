import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, HEADERS, authHeaders } from './config.js';

export const LOAD_TEST_PASSWORD = 'password123';

// 회원가입
export function signup(email, password, nickname) {
    const res = http.post(`${BASE_URL}/api/auth/signup`, JSON.stringify({
        email: email,
        password: password,
        nickname: nickname,
    }), { headers: HEADERS });
    return res;
}

// 로그인 → JWT 토큰 반환
export function login(email, password) {
    const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({
        email: email,
        password: password,
    }), { headers: HEADERS });

    check(res, { 'login success': (r) => r.status === 200 });

    if (res.status === 200) {
        const body = JSON.parse(res.body);
        return {
            token: body.token,
            userId: body.userId,
            email: body.email,
            nickname: body.nickname,
        };
    }
    return null;
}

// 좌석 목록 조회
export function getSeats(token, concertId, scheduleId) {
    const res = http.get(
        `${BASE_URL}/api/concerts/${concertId}/schedules/${scheduleId}/seats`,
        { headers: authHeaders(token) }
    );
    return res;
}

// 대기열 진입
export function enterQueue(token, scheduleId) {
    return http.post(`${BASE_URL}/api/queue/enter`, JSON.stringify({
        scheduleId: scheduleId,
    }), { headers: authHeaders(token) });
}

// 입장 토큰 발급
export function issueQueueToken(token, scheduleId) {
    const enterRes = enterQueue(token, scheduleId);
    if (enterRes.status !== 201) {
        return null;
    }

    const tokenRes = http.get(
        `${BASE_URL}/api/queue/token?scheduleId=${scheduleId}`,
        { headers: authHeaders(token) }
    );
    if (tokenRes.status === 200) {
        return JSON.parse(tokenRes.body).token;
    }
    return null;
}

// queueToken을 직접 지정해 예매 요청. 토큰 abuse/idempotency 시나리오에서 사용한다.
export function directReserve(token, scheduleId, seatIds, queueToken, idempotencyKey = null) {
    const headers = {
        ...authHeaders(token),
        'Idempotency-Key': idempotencyKey || reservationIdempotencyKey(scheduleId, seatIds),
    };

    const payload = {
        scheduleId: scheduleId,
        seatIds: seatIds,
    };
    if (queueToken !== undefined) {
        payload.queueToken = queueToken;
    }

    const res = http.post(`${BASE_URL}/api/reservations`, JSON.stringify({
        ...payload,
    }), { headers: headers });
    return res;
}

// 예매 요청: 정상 사용자는 예매 직전 fresh 입장 토큰을 발급받는다.
export function reserve(token, scheduleId, seatIds, idempotencyKey = null) {
    const queueToken = issueQueueToken(token, scheduleId);
    if (!queueToken) {
        return { status: 0, body: 'queue token issue failed' };
    }

    return directReserve(token, scheduleId, seatIds, queueToken, idempotencyKey);
}

export function pay(token, reservationId, idempotencyKey = null) {
    const headers = {
        ...authHeaders(token),
        'Idempotency-Key': idempotencyKey || `payment-${reservationId}-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`,
    };

    return http.post(`${BASE_URL}/api/payments`, JSON.stringify({
        reservationId: reservationId,
    }), { headers: headers });
}

export function cancelReservation(token, reservationId) {
    return http.del(`${BASE_URL}/api/reservations/${reservationId}`, null, {
        headers: authHeaders(token),
    });
}

function reservationIdempotencyKey(scheduleId, seatIds) {
    return `reservation-${scheduleId}-${seatIds.join('-')}-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`;
}

// k6 전용 fixture 리셋: !prod profile에서만 노출되는 endpoint를 사용한다.
export function resetLoadTestData(scheduleId, userCount = 200) {
    const res = http.post(`${BASE_URL}/api/admin/load-test/reset?scheduleId=${scheduleId}&userCount=${userCount}`, null, {
        headers: HEADERS,
    });
    const body = res.status === 200 ? JSON.parse(res.body) : {};
    check(res, {
        'load-test reset success': (r) => r.status === 200,
        'load-test reset available seats = 50': () => body.availableSeatCount === 50,
        'load-test reset redis stock = 50': () => body.redisStock === 50,
    });
    if (res.status !== 200) {
        throw new Error(`load-test reset failed: ${res.status} ${res.body}`);
    }
    return body;
}

// 이전 A/B/C 스크립트 호환용 alias
export function resetData(scheduleId) {
    return resetLoadTestData(scheduleId);
}

export function summary(scheduleId) {
    const res = http.get(`${BASE_URL}/api/admin/load-test/summary?scheduleId=${scheduleId}`, {
        headers: HEADERS,
    });
    check(res, { 'load-test summary success': (r) => r.status === 200 });
    if (res.status === 200) {
        return JSON.parse(res.body);
    }
    return res;
}

export function expireReservation(reservationId) {
    return http.post(`${BASE_URL}/api/admin/load-test/reservations/${reservationId}/expire`, null, {
        headers: HEADERS,
    });
}

export function expireQueueToken(userId, scheduleId) {
    return http.post(`${BASE_URL}/api/admin/load-test/tokens/expire?userId=${userId}&scheduleId=${scheduleId}`, null, {
        headers: HEADERS,
    });
}

export function loadTestEmail(index) {
    return `loadtest-user-${index}@k6.local`;
}

export function loginLoadTestUsers(count, password = LOAD_TEST_PASSWORD) {
    const users = [];

    for (let i = 0; i < count; i++) {
        const email = loadTestEmail(i);
        const user = login(email, password);

        if (user && user.token) {
            users.push({ ...user, index: i });
        }
    }

    return users;
}

// setup에서 사용자 일괄 준비 + 로그인. load-test reset이 사용자를 보장한다.
export function setupUsers(count) {
    return loginLoadTestUsers(count, LOAD_TEST_PASSWORD);
}
