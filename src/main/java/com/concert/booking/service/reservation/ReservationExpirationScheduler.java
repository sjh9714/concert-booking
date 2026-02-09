package com.concert.booking.service.reservation;

import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.event.ReservationCancelledEvent;
import com.concert.booking.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationExpirationScheduler {

    private final ReservationRepository reservationRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // 30초마다 실행, ShedLock으로 서버 2대 중복 실행 방지
    @Scheduled(fixedRate = 30000)
    @SchedulerLock(name = "expireReservations", lockAtLeastFor = "10s", lockAtMostFor = "30s")
    @Transactional
    public void expireReservations() {
        LocalDateTime now = LocalDateTime.now();
        List<Reservation> expiredReservations =
                reservationRepository.findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, now);

        if (expiredReservations.isEmpty()) {
            return;
        }

        log.info("만료 예매 처리 시작: {}건", expiredReservations.size());

        for (Reservation reservation : expiredReservations) {
            try {
                // PENDING → EXPIRED
                reservation.expire();

                // 좌석 ID 추출
                List<Long> seatIds = reservation.getReservationSeats().stream()
                        .map(rs -> rs.getSeat().getId())
                        .toList();

                // Kafka 이벤트 발행 → Consumer(seat-release)가 좌석 반환 처리
                ReservationCancelledEvent event = new ReservationCancelledEvent(
                        reservation.getId(),
                        reservation.getUser().getId(),
                        reservation.getSchedule().getId(),
                        seatIds,
                        reservation.getTotalAmount(),
                        "EXPIRED"
                );

                kafkaTemplate.send("reservation.cancelled",
                        String.valueOf(reservation.getId()), event);

                log.info("만료 예매 이벤트 발행: reservationId={}", reservation.getId());

            } catch (Exception e) {
                log.error("만료 예매 처리 실패: reservationId={}", reservation.getId(), e);
            }
        }

        log.info("만료 예매 처리 완료: {}건", expiredReservations.size());
    }
}
