package com.concert.booking.service.queue;

import com.concert.booking.common.exception.QueueNotReadyException;
import com.concert.booking.common.exception.ResourceNotFoundException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.QueueProperties;
import com.concert.booking.dto.queue.QueuePositionResponse;
import com.concert.booking.dto.queue.QueueStatus;
import com.concert.booking.dto.queue.QueueTokenResponse;
import com.concert.booking.observability.BookingMetrics;
import com.concert.booking.repository.ConcertScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final RedisTemplate<String, String> redisTemplate;
    private final BookingMetrics bookingMetrics;
    private final QueueProperties properties;
    private final ConcertScheduleRepository concertScheduleRepository;

    private static final DefaultRedisScript<String> ENTER_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 'ADMITTED'
            end

            redis.call('DEL', KEYS[3])
            redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
            return 'QUEUED'
            """, String.class);

    private static final DefaultRedisScript<String> POSITION_SCRIPT = new DefaultRedisScript<>("""
            local total = redis.call('ZCARD', KEYS[1])
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 'ADMITTED||' .. total
            end

            if redis.call('EXISTS', KEYS[3]) == 1 then
                return 'EXPIRED||' .. total
            end

            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if not rank then
                return 'NOT_JOINED||' .. total
            end

            local status = 'WAITING'
            if rank < tonumber(ARGV[2]) then
                status = 'READY'
            end
            return status .. '|' .. (rank + 1) .. '|' .. total
            """, String.class);

    private static final DefaultRedisScript<String> ISSUE_TOKEN_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[2])
            if existing then
                if redis.call('EXISTS', KEYS[3]) == 0 then
                    redis.call('SET', KEYS[3], 'ADMITTED', 'PX', redis.call('PTTL', KEYS[2]) + tonumber(ARGV[5]))
                end
                return 'ADMITTED|' .. existing .. '|' .. redis.call('PTTL', KEYS[2])
            end

            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if not rank then
                return 'NOT_JOINED||-1'
            end
            if rank >= tonumber(ARGV[2]) then
                return 'WAITING||-1'
            end

            redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[4])
            redis.call('SET', KEYS[3], 'ADMITTED', 'PX', tonumber(ARGV[4]) + tonumber(ARGV[5]))
            redis.call('ZREM', KEYS[1], ARGV[1])
            return 'ISSUED|' .. ARGV[3] .. '|' .. redis.call('PTTL', KEYS[2])
            """, String.class);

    // 토큰 확인과 ZADD NX를 하나의 Lua script로 묶어 토큰 발급 직후 재삽입되는 race를 막는다.
    public QueuePositionResponse enter(Long userId, Long scheduleId) {
        if (!concertScheduleRepository.existsById(scheduleId)) {
            throw new ResourceNotFoundException("스케줄을 찾을 수 없습니다: " + scheduleId);
        }
        String queueKey = RedisKeyUtil.queueKey(scheduleId);
        String tokenKey = RedisKeyUtil.tokenKey(userId, scheduleId);
        redisTemplate.execute(
                ENTER_SCRIPT,
                List.of(queueKey, tokenKey, RedisKeyUtil.admissionMarkerKey(userId, scheduleId)),
                String.valueOf(userId),
                String.valueOf(System.currentTimeMillis())
        );

        return getPosition(userId, scheduleId);
    }

    // 순위 조회: ZRANK (0-based → 1-based)
    public QueuePositionResponse getPosition(Long userId, Long scheduleId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String raw = redisTemplate.execute(
                POSITION_SCRIPT,
                List.of(
                        RedisKeyUtil.queueKey(scheduleId),
                        RedisKeyUtil.tokenKey(userId, scheduleId),
                        RedisKeyUtil.admissionMarkerKey(userId, scheduleId)),
                String.valueOf(userId),
                String.valueOf(properties.getEntryThreshold())
        );
        if (raw == null) {
            throw new IllegalStateException("대기열 순위 조회 결과를 확인할 수 없습니다.");
        }

        String[] result = raw.split("\\|", -1);
        QueueStatus status = QueueStatus.valueOf(result[0]);
        Long position = result[1].isEmpty() ? null : Long.parseLong(result[1]);
        long totalWaiting = Long.parseLong(result[2]);
        return new QueuePositionResponse(status, position, totalWaiting, now);
    }

    // 순위 확인, 토큰 저장, 대기열 제거를 Redis 서버에서 원자적으로 실행한다.
    public QueueTokenResponse issueToken(Long userId, Long scheduleId) {
        String queueKey = RedisKeyUtil.queueKey(scheduleId);
        String tokenKey = RedisKeyUtil.tokenKey(userId, scheduleId);
        String candidate = UUID.randomUUID().toString();
        String raw = redisTemplate.execute(
                ISSUE_TOKEN_SCRIPT,
                List.of(queueKey, tokenKey, RedisKeyUtil.admissionMarkerKey(userId, scheduleId)),
                String.valueOf(userId),
                String.valueOf(properties.getEntryThreshold()),
                candidate,
                String.valueOf(properties.getTokenTtl().toMillis()),
                String.valueOf(properties.getExpiryTombstoneTtl().toMillis())
        );
        if (raw == null) {
            throw new IllegalStateException("대기열 토큰 발급 결과를 확인할 수 없습니다.");
        }

        String[] result = raw.split("\\|", -1);
        if (result[0].equals("WAITING") || result[0].equals("NOT_JOINED")) {
            throw new QueueNotReadyException("아직 입장 순서가 아닙니다. 대기열에서 기다려주세요.");
        }
        if (result[0].equals("ISSUED")) {
            bookingMetrics.recordQueueTokenIssued();
        }

        long ttlMillis = Long.parseLong(result[2]);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plus(Duration.ofMillis(Math.max(0, ttlMillis)));
        return new QueueTokenResponse(result[1], scheduleId, expiresAt);
    }

    // 토큰 검증: GET + 값 비교 + userId/scheduleId 바인딩 확인
    public boolean validateToken(Long userId, Long scheduleId, String token) {
        String tokenKey = RedisKeyUtil.tokenKey(userId, scheduleId);
        String storedToken = redisTemplate.opsForValue().get(tokenKey);
        boolean valid = token != null && token.equals(storedToken);
        if (!valid) {
            bookingMetrics.recordQueueTokenValidationFailure();
        }
        return valid;
    }

    // 토큰 소비: DEL (예매 성공 시 1회 사용)
    public void consumeToken(Long userId, Long scheduleId) {
        String tokenKey = RedisKeyUtil.tokenKey(userId, scheduleId);
        redisTemplate.delete(tokenKey);
        redisTemplate.delete(RedisKeyUtil.admissionMarkerKey(userId, scheduleId));
    }

    // 대기열 제거: ZREM
    public void removeFromQueue(Long userId, Long scheduleId) {
        String queueKey = RedisKeyUtil.queueKey(scheduleId);
        redisTemplate.opsForZSet().remove(queueKey, String.valueOf(userId));
    }

}
