package com.concert.booking.service.queue;

import com.concert.booking.common.exception.QueueNotReadyException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.dto.queue.QueuePositionResponse;
import com.concert.booking.dto.queue.QueueTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final int ENTRY_THRESHOLD = 100; // 100등 이내 입장 가능
    private static final int TOKEN_TTL_SECONDS = 300; // 5분

    // 대기열 진입: ZADD NX (중복 방지, score = timestamp)
    public QueuePositionResponse enter(Long userId, Long scheduleId) {
        String queueKey = RedisKeyUtil.queueKey(scheduleId);
        double score = System.currentTimeMillis();

        // NX: 이미 대기 중이면 무시 (중복 진입·순번 조작 방지)
        redisTemplate.opsForZSet().addIfAbsent(queueKey, String.valueOf(userId), score);

        return getPosition(userId, scheduleId);
    }

    // 순위 조회: ZRANK (0-based → 1-based)
    public QueuePositionResponse getPosition(Long userId, Long scheduleId) {
        String queueKey = RedisKeyUtil.queueKey(scheduleId);
        Long rank = redisTemplate.opsForZSet().rank(queueKey, String.valueOf(userId));
        Long totalWaiting = redisTemplate.opsForZSet().size(queueKey);

        if (rank == null) {
            return new QueuePositionResponse(0L, totalWaiting != null ? totalWaiting : 0L, "대기열에 없습니다.");
        }

        long position = rank + 1; // 0-based → 1-based
        String estimatedWaitTime = estimateWaitTime(position);

        return new QueuePositionResponse(position, totalWaiting != null ? totalWaiting : 0L, estimatedWaitTime);
    }

    // 입장 토큰 발급: 순위 ≤ threshold → UUID 생성, Redis SET EX 300
    public QueueTokenResponse issueToken(Long userId, Long scheduleId) {
        String queueKey = RedisKeyUtil.queueKey(scheduleId);
        Long rank = redisTemplate.opsForZSet().rank(queueKey, String.valueOf(userId));

        if (rank == null || rank + 1 > ENTRY_THRESHOLD) {
            throw new QueueNotReadyException("아직 입장 순서가 아닙니다. 대기열에서 기다려주세요.");
        }

        String token = UUID.randomUUID().toString();
        String tokenKey = RedisKeyUtil.tokenKey(userId, scheduleId);
        redisTemplate.opsForValue().set(tokenKey, token, TOKEN_TTL_SECONDS, TimeUnit.SECONDS);

        // 대기열에서 제거
        removeFromQueue(userId, scheduleId);

        return new QueueTokenResponse(token, scheduleId);
    }

    // 토큰 검증: GET + 값 비교 + userId/scheduleId 바인딩 확인
    public boolean validateToken(Long userId, Long scheduleId, String token) {
        String tokenKey = RedisKeyUtil.tokenKey(userId, scheduleId);
        String storedToken = redisTemplate.opsForValue().get(tokenKey);
        return token != null && token.equals(storedToken);
    }

    // 토큰 소비: DEL (예매 성공 시 1회 사용)
    public void consumeToken(Long userId, Long scheduleId) {
        String tokenKey = RedisKeyUtil.tokenKey(userId, scheduleId);
        redisTemplate.delete(tokenKey);
    }

    // 대기열 제거: ZREM
    public void removeFromQueue(Long userId, Long scheduleId) {
        String queueKey = RedisKeyUtil.queueKey(scheduleId);
        redisTemplate.opsForZSet().remove(queueKey, String.valueOf(userId));
    }

    private String estimateWaitTime(long position) {
        if (position <= ENTRY_THRESHOLD) {
            return "곧 입장 가능합니다.";
        }
        // 약 10초당 100명 처리 가정
        long waitSeconds = (position - ENTRY_THRESHOLD) / 10;
        if (waitSeconds < 60) {
            return "약 " + Math.max(1, waitSeconds) + "초";
        }
        return "약 " + (waitSeconds / 60) + "분";
    }
}
