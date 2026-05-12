package com.concert.booking.service.queue;

import com.concert.booking.common.exception.InvalidQueueTokenException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.observability.BookingMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class QueueTokenGuard {

    private static final int IN_FLIGHT_TTL_SECONDS = 300;

    private final RedisTemplate<String, String> redisTemplate;
    private final BookingMetrics bookingMetrics;

    public TokenLease acquire(Long userId, Long scheduleId, String token) {
        if (!StringUtils.hasText(token)) {
            bookingMetrics.recordQueueTokenValidationFailure();
            throw new InvalidQueueTokenException("입장 토큰은 필수입니다.");
        }

        String tokenKey = RedisKeyUtil.tokenKey(userId, scheduleId);
        String inFlightKey = RedisKeyUtil.tokenInFlightKey(userId, scheduleId);

        validateStoredToken(tokenKey, token);

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(inFlightKey, token, IN_FLIGHT_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            bookingMetrics.recordQueueTokenInFlightConflict();
            throw new InvalidQueueTokenException("이미 처리 중인 입장 토큰입니다.");
        }

        try {
            validateStoredToken(tokenKey, token);
            return new TokenLease(tokenKey, inFlightKey);
        } catch (RuntimeException e) {
            redisTemplate.delete(inFlightKey);
            throw e;
        }
    }

    public void consume(TokenLease lease) {
        redisTemplate.delete(lease.tokenKey());
        redisTemplate.delete(lease.inFlightKey());
    }

    public void release(TokenLease lease) {
        redisTemplate.delete(lease.inFlightKey());
    }

    private void validateStoredToken(String tokenKey, String token) {
        String storedToken = redisTemplate.opsForValue().get(tokenKey);
        if (!token.equals(storedToken)) {
            bookingMetrics.recordQueueTokenValidationFailure();
            throw new InvalidQueueTokenException("유효하지 않은 입장 토큰입니다.");
        }
    }

    public record TokenLease(String tokenKey, String inFlightKey) {
    }
}
