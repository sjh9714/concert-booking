import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, HEADERS, authHeaders } from './config.js';

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
        return JSON.parse(res.body).token;
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

// 예매 요청
export function reserve(token, scheduleId, seatIds) {
    const res = http.post(`${BASE_URL}/api/reservations`, JSON.stringify({
        scheduleId: scheduleId,
        seatIds: seatIds,
    }), { headers: authHeaders(token) });
    return res;
}

// 데이터 리셋
export function resetData(scheduleId) {
    const res = http.post(`${BASE_URL}/api/admin/reset?scheduleId=${scheduleId}`, null, {
        headers: HEADERS,
    });
    check(res, { 'reset success': (r) => r.status === 200 });
    return res;
}

// setup에서 사용자 일괄 생성 + 로그인
export function setupUsers(count, prefix) {
    const users = [];
    const password = 'password123';

    for (let i = 0; i < count; i++) {
        const email = `${prefix}-${i}@k6test.com`;
        const nickname = `${prefix}${i}`;

        signup(email, password, nickname);
        const token = login(email, password);

        if (token) {
            users.push({ email, token, index: i });
        }
    }

    return users;
}
