package com.concert.booking.common.util;

public final class RedisKeyUtil {

    private RedisKeyUtil() {
    }

    // 대기열 (Sorted Set)
    public static String queueKey(Long scheduleId) {
        return "queue:schedule:" + scheduleId;
    }

    // 입장 토큰 (String + TTL)
    public static String tokenKey(Long userId, Long scheduleId) {
        return "token:queue:" + userId + ":" + scheduleId;
    }

    // 활성 처리 카운터
    public static String activeKey(Long scheduleId) {
        return "active:schedule:" + scheduleId;
    }

    // 재고 선검증 (atomic decrement)
    public static String stockKey(Long scheduleId) {
        return "stock:schedule:" + scheduleId;
    }

    // 좌석 임시 점유 (String + TTL)
    public static String seatHoldKey(Long seatId) {
        return "hold:seat:" + seatId;
    }
}
