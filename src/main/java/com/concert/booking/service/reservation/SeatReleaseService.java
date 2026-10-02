package com.concert.booking.service.reservation;

import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.repository.SeatRepository;
import org.springframework.beans.factory.annotation.Value;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.domain.Reservation;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final SeatRepository seatRepository;
    @Value("${reservation.service-mode:false}")
    private boolean serviceMode;

    @Transactional
    public SeatReleaseResult releaseHeldSeats(Long reservationId, String reason) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElse(null);
        if (reservation == null) {
            return new SeatReleaseResult(reservationId, 0, false);
        }

        if (reservation.getStatus() != ReservationStatus.CANCELLED
                && reservation.getStatus() != ReservationStatus.EXPIRED) {
            return new SeatReleaseResult(reservationId, 0, true);
        }
        List<Long> seatIds = reservationSeatRepository.findByReservationId(reservationId).stream()
                .map(rs -> rs.getSeat().getId()).sorted().toList();
        int releasedCount = 0;
        for (var seat : seatRepository.findAllByIdForUpdate(seatIds)) {
            if (seat.release(reservationId)) {
                releasedCount++;
                if (!serviceMode) redisTemplate.delete(RedisKeyUtil.seatHoldKey(seat.getId()));
            }
        }
        if (!serviceMode && releasedCount > 0) {
            reservation.getSchedule().increaseAvailableSeats(releasedCount);
            incrementRedisStockIfPresent(reservation.getSchedule().getId(), releasedCount);
        }

        log.info("좌석 반환 처리: reservationId={}, reason={}, releasedCount={}",
                reservationId, reason, releasedCount);
        return new SeatReleaseResult(reservationId, releasedCount, true);
    }

    private void incrementRedisStockIfPresent(Long scheduleId, int releasedCount) {
        String stockKey = RedisKeyUtil.stockKey(scheduleId);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(stockKey))) {
            redisTemplate.opsForValue().increment(stockKey, releasedCount);
        }
    }

    public record SeatReleaseResult(Long reservationId, int releasedCount, boolean reservationFound) {
    }
}
