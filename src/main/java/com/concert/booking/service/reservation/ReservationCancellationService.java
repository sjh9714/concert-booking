package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.ForbiddenException;
import com.concert.booking.common.exception.InvalidReservationStateException;
import com.concert.booking.common.exception.ReservationNotFoundException;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.service.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationCancellationService {

    private final ReservationRepository reservationRepository;
    private final OutboxEventService outboxEventService;

    @Transactional
    public void cancel(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("예매를 찾을 수 없습니다."));

        if (!reservation.getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인의 예매만 취소할 수 있습니다.");
        }

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return;
        }
        if (!reservation.canCancel()) {
            throw new InvalidReservationStateException(
                    "대기 중인 예매만 취소할 수 있습니다. 현재 상태: " + reservation.getStatus());
        }

        reservation.cancel();
        outboxEventService.saveReservationCancelled(reservation);
    }
}
