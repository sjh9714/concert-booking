package com.concert.booking.consumer;

import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationSeat;
import com.concert.booking.domain.SeatStatus;
import com.concert.booking.event.ReservationCancelledEvent;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseConsumer {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @KafkaListener(topics = "reservation.cancelled", groupId = "seat-release")
    @Transactional
    public void handleCancelledReservation(ReservationCancelledEvent event, Acknowledgment ack) {
        log.info("좌석 반환 이벤트 수신: reservationId={}, reason={}", event.reservationId(), event.reason());

        try {
            Reservation reservation = reservationRepository.findById(event.reservationId())
                    .orElse(null);

            if (reservation == null) {
                log.warn("예매를 찾을 수 없습니다: reservationId={}", event.reservationId());
                ack.acknowledge();
                return;
            }

            // 좌석 반환
            List<ReservationSeat> reservationSeats = reservationSeatRepository.findByReservationId(event.reservationId());
            int releasedCount = 0;

            for (ReservationSeat rs : reservationSeats) {
                // 멱등성: 이미 AVAILABLE이면 skip
                if (rs.getSeat().getStatus() == SeatStatus.HELD) {
                    rs.getSeat().release();
                    releasedCount++;

                    // Redis 좌석 홀드 삭제
                    redisTemplate.delete(RedisKeyUtil.seatHoldKey(rs.getSeat().getId()));
                }
            }

            // 잔여 좌석 복원
            if (releasedCount > 0) {
                reservation.getSchedule().increaseAvailableSeats(releasedCount);

                // Redis 재고 복원
                String stockKey = RedisKeyUtil.stockKey(event.scheduleId());
                redisTemplate.opsForValue().increment(stockKey, releasedCount);
            }

            log.info("좌석 반환 완료: reservationId={}, releasedCount={}", event.reservationId(), releasedCount);

            // manual commit
            ack.acknowledge();

        } catch (Exception e) {
            log.error("좌석 반환 처리 실패: reservationId={}", event.reservationId(), e);
            throw e; // 재시도 트리거
        }
    }
}
