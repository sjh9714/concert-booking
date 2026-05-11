package com.concert.booking.service.stock;

import com.concert.booking.common.exception.SoldOutException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.domain.SeatStatus;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RedisStockService {

    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional(readOnly = true)
    public StockSnapshot initialize(Long scheduleId, boolean overwrite) {
        concertScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다."));

        String stockKey = RedisKeyUtil.stockKey(scheduleId);
        int availableSeatCount = availableSeatCount(scheduleId);

        if (overwrite) {
            redisTemplate.opsForValue().set(stockKey, String.valueOf(availableSeatCount));
            return new StockSnapshot(scheduleId, availableSeatCount, availableSeatCount, true, true);
        }

        Boolean initialized = redisTemplate.opsForValue()
                .setIfAbsent(stockKey, String.valueOf(availableSeatCount));
        Integer redisStock = readRedisStock(scheduleId);
        return new StockSnapshot(
                scheduleId,
                availableSeatCount,
                redisStock,
                Boolean.TRUE.equals(initialized),
                false
        );
    }

    public StockLease tryAcquire(Long scheduleId, int seatCount) {
        if (seatCount <= 0) {
            throw new IllegalArgumentException("선점할 좌석 수는 1개 이상이어야 합니다.");
        }

        initialize(scheduleId, false);

        String stockKey = RedisKeyUtil.stockKey(scheduleId);
        StockLease lease = new StockLease(redisTemplate, stockKey, seatCount);
        Long remaining = redisTemplate.opsForValue().decrement(stockKey, seatCount);
        if (remaining == null || remaining < 0) {
            lease.restoreOnce();
            throw new SoldOutException("잔여 좌석이 부족합니다.");
        }
        return lease;
    }

    public Integer readRedisStock(Long scheduleId) {
        String value = redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scheduleId));
        if (value == null) {
            return null;
        }
        return Integer.parseInt(value);
    }

    private int availableSeatCount(Long scheduleId) {
        return Math.toIntExact(seatRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.AVAILABLE));
    }

    public record StockSnapshot(
            Long scheduleId,
            int availableSeatCount,
            Integer redisStock,
            boolean initialized,
            boolean overwritten
    ) {
    }

    public static class StockLease {
        private final RedisTemplate<String, String> redisTemplate;
        private final String stockKey;
        private final int seatCount;
        private boolean completed;
        private boolean restored;

        private StockLease(RedisTemplate<String, String> redisTemplate, String stockKey, int seatCount) {
            this.redisTemplate = redisTemplate;
            this.stockKey = stockKey;
            this.seatCount = seatCount;
        }

        public void complete() {
            this.completed = true;
        }

        public void restoreOnce() {
            if (!completed && !restored) {
                redisTemplate.opsForValue().increment(stockKey, seatCount);
                restored = true;
            }
        }
    }
}
