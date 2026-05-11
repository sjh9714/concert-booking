package com.concert.booking.consumer;

import com.concert.booking.event.ReservationCancelledEvent;
import com.concert.booking.service.reservation.SeatReleaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseConsumer {

    private final SeatReleaseService seatReleaseService;
    private final SeatReleaseFailurePolicy seatReleaseFailurePolicy;

    @KafkaListener(topics = "reservation.cancelled", groupId = "${kafka.consumer.seat-release-group:seat-release}")
    public void handleCancelledReservation(ReservationCancelledEvent event, Acknowledgment ack) {
        log.info("좌석 반환 이벤트 수신: reservationId={}, reason={}", event.reservationId(), event.reason());

        try {
            seatReleaseFailurePolicy.beforeRelease(event);
            SeatReleaseService.SeatReleaseResult result =
                    seatReleaseService.releaseHeldSeats(event.reservationId(), event.reason());
            if (!result.reservationFound()) {
                log.warn("예매를 찾을 수 없습니다: reservationId={}", event.reservationId());
                ack.acknowledge();
                return;
            }

            log.info("좌석 반환 완료: reservationId={}, releasedCount={}",
                    event.reservationId(), result.releasedCount());

            // manual commit
            ack.acknowledge();

        } catch (Exception e) {
            log.error("좌석 반환 처리 실패: reservationId={}", event.reservationId(), e);
            throw e; // 재시도 트리거
        }
    }
}
