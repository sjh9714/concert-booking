package com.concert.booking.controller;

import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Seat;
import com.concert.booking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
    private final ReservationSeatRepository reservationSeatRepository;
    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @PostMapping("/reset")
    @Transactional
    public ResponseEntity<Void> resetData(@RequestParam Long scheduleId) {
        log.info("데이터 리셋 시작: scheduleId={}", scheduleId);

        // 1. FK 순서대로 삭제: payments → reservation_seats → reservations
        paymentRepository.deleteByScheduleId(scheduleId);
        reservationSeatRepository.deleteByScheduleId(scheduleId);
        reservationRepository.deleteByScheduleId(scheduleId);

        // 2. 좌석 상태 초기화
        seatRepository.resetSeatsByScheduleId(scheduleId);

        // 3. 스케줄 잔여 좌석 복원
        concertScheduleRepository.resetAvailableSeats(scheduleId);

        // 4. Redis 초기화
        resetRedis(scheduleId);

        log.info("데이터 리셋 완료: scheduleId={}", scheduleId);
        return ResponseEntity.ok().build();
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

        // 재고 재설정 (분산 락 전략용)
        ConcertSchedule schedule = concertScheduleRepository.findById(scheduleId).orElse(null);
        if (schedule != null) {
            redisTemplate.opsForValue().set(
                    RedisKeyUtil.stockKey(scheduleId),
                    String.valueOf(schedule.getTotalSeats())
            );
        }
    }
}
