package com.concert.booking.service.reservation;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 기본 서비스: 선택한 좌석만 DB에서 잠근다. 공통 예약·조회·취소 흐름은 재사용한다. */
@Service
@Primary
@Profile("service")
public class SeatLockReservationService extends PessimisticLockReservationService {
    public SeatLockReservationService(ReservationOrchestrator orchestrator,
            ReservationQueryService queries, ReservationCancellationService cancellations) {
        super(orchestrator, queries, cancellations);
    }

    @Override
    public ReservationCreationMode creationMode() {
        return ReservationCreationMode.SEAT_LOCK;
    }
}
