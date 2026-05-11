package com.concert.booking.controller;

import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.domain.Seat;
import com.concert.booking.repository.*;
import com.concert.booking.service.kafka.DltReplayService;
import com.concert.booking.service.stock.RedisStockService;
import com.concert.booking.service.stock.StockReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final PaymentRepository paymentRepository;
    private final ReservationIdempotencyKeyRepository reservationIdempotencyKeyRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final DltReplayService dltReplayService;
    private final RedisStockService redisStockService;
    private final StockReconciliationService stockReconciliationService;

    @PostMapping("/reset")
    @Transactional
    public ResponseEntity<Void> resetData(@RequestParam Long scheduleId) {
        log.info("데이터 리셋 시작: scheduleId={}", scheduleId);

        // 1. FK 순서대로 삭제: payments → idempotency keys → reservation_seats → reservations
        paymentRepository.deleteByScheduleId(scheduleId);
        reservationIdempotencyKeyRepository.deleteByScheduleId(scheduleId);
        reservationSeatRepository.deleteByScheduleId(scheduleId);
        reservationRepository.deleteByScheduleId(scheduleId);

        // 2. 좌석 상태 초기화
        seatRepository.resetSeatsByScheduleId(scheduleId);

        // 3. 스케줄 잔여 좌석 복원
        concertScheduleRepository.resetAvailableSeats(scheduleId);

        // 4. Redis 초기화
        resetRedis(scheduleId);
        redisStockService.initialize(scheduleId, true);

        log.info("데이터 리셋 완료: scheduleId={}", scheduleId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/dlt/replay")
    public ResponseEntity<DltReplayService.ReplayResult> replayDlt(
            @RequestParam String topic,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(dltReplayService.replay(topic, limit));
    }

    @PostMapping("/schedules/{scheduleId}/stock/initialize")
    public ResponseEntity<RedisStockService.StockSnapshot> initializeStock(
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "false") boolean overwrite) {
        return ResponseEntity.ok(redisStockService.initialize(scheduleId, overwrite));
    }

    @PostMapping("/schedules/{scheduleId}/stock/reconcile")
    public ResponseEntity<StockReconciliationService.ReconciliationResult> reconcileStock(
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "false") boolean repair) {
        return ResponseEntity.ok(stockReconciliationService.reconcile(scheduleId, repair));
    }

    private void resetRedis(Long scheduleId) {
        // 재고 키 삭제
        redisTemplate.delete(RedisKeyUtil.stockKey(scheduleId));

        // 대기열 키 삭제
        redisTemplate.delete(RedisKeyUtil.queueKey(scheduleId));
        redisTemplate.delete(RedisKeyUtil.activeKey(scheduleId));

        // 좌석 홀드 키 삭제
        List<Seat> seats = seatRepository.findByScheduleId(scheduleId);
        for (Seat seat : seats) {
            redisTemplate.delete(RedisKeyUtil.seatHoldKey(seat.getId()));
        }

        // 토큰 키 삭제 (패턴 매칭)
        Set<String> tokenKeys = redisTemplate.keys("token:queue:*:" + scheduleId);
        if (tokenKeys != null && !tokenKeys.isEmpty()) {
            redisTemplate.delete(tokenKeys);
        }

        // stock 재설정은 resetData()에서 DB AVAILABLE 좌석 수 기준으로 수행한다.
    }
}
