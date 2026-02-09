export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const CONCERT_ID = parseInt(__ENV.CONCERT_ID || '1');
export const SCHEDULE_ID = parseInt(__ENV.SCHEDULE_ID || '1');

// VU 수 (로컬 환경에 맞게 축소: DESIGN.md의 1,000 → 100)
export const VUS = parseInt(__ENV.VUS || '100');

// 테스트 지속 시간
export const DURATION = __ENV.DURATION || '30s';
export const RAMP_UP = __ENV.RAMP_UP || '10s';

// 공통 HTTP 옵션
export const HEADERS = {
    'Content-Type': 'application/json',
};

export function authHeaders(token) {
    return {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
    };
}
