package com.concert.booking.service.stock;

import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.SeatStatus;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockReconciliationService {

    private static final String SCHEDULE_AVAILABLE_SEATS_MISMATCH = "SCHEDULE_AVAILABLE_SEATS_MISMATCH";
    private static final String REDIS_STOCK_MISMATCH = "REDIS_STOCK_MISMATCH";
    private static final String REDIS_STOCK_MISSING = "REDIS_STOCK_MISSING";

    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;
    private final RedisStockService redisStockService;

    @Transactional
    public ReconciliationResult reconcile(Long scheduleId, boolean repair) {
        ConcertSchedule schedule = concertScheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다."));

        int availableCount = count(scheduleId, SeatStatus.AVAILABLE);
        int heldCount = count(scheduleId, SeatStatus.HELD);
        int reservedCount = count(scheduleId, SeatStatus.RESERVED);
        Integer redisStock = redisStockService.readRedisStock(scheduleId);

        List<String> mismatches = mismatches(schedule, availableCount, redisStock);
        if (repair && !mismatches.isEmpty()) {
            schedule.syncAvailableSeats(availableCount);
            redisStockService.initialize(scheduleId, true);
            return new ReconciliationResult(
                    scheduleId,
                    availableCount,
                    heldCount,
                    reservedCount,
                    availableCount,
                    availableCount,
                    mismatches,
                    true
            );
        }

        return new ReconciliationResult(
                scheduleId,
                availableCount,
                heldCount,
                reservedCount,
                schedule.getAvailableSeats(),
                redisStock,
                mismatches,
                false
        );
    }

    private List<String> mismatches(ConcertSchedule schedule, int availableCount, Integer redisStock) {
        List<String> mismatches = new ArrayList<>();
        if (schedule.getAvailableSeats() != availableCount) {
            mismatches.add(SCHEDULE_AVAILABLE_SEATS_MISMATCH);
        }
        if (redisStock == null) {
            mismatches.add(REDIS_STOCK_MISSING);
        } else if (redisStock != availableCount) {
            mismatches.add(REDIS_STOCK_MISMATCH);
        }
        return List.copyOf(mismatches);
    }

    private int count(Long scheduleId, SeatStatus status) {
        return Math.toIntExact(seatRepository.countByScheduleIdAndStatus(scheduleId, status));
    }

    public record ReconciliationResult(
            Long scheduleId,
            int availableSeatCount,
            int heldSeatCount,
            int reservedSeatCount,
            int scheduleAvailableSeats,
            Integer redisStock,
            List<String> mismatches,
            boolean repaired
    ) {
    }
}
