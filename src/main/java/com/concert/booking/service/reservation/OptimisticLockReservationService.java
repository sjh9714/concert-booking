package com.concert.booking.service.reservation;

import com.concert.booking.dto.reservation.ReservationDetailResponse;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptimisticLockReservationService implements ReservationService, SeatReservationStrategy {

    private final ReservationOrchestrator reservationOrchestrator;
    private final ReservationQueryService reservationQueryService;
    private final ReservationCancellationService reservationCancellationService;

    @Override
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2)
    )
    public ReservationResponse reserve(Long userId, ReservationRequest request, String idempotencyKey) {
        return reservationOrchestrator.reserve(userId, request, idempotencyKey, this);
    }

    @Override
    public ReservationCreationMode creationMode() {
        return ReservationCreationMode.OPTIMISTIC;
    }

    @Override
    public ReservationResponse execute(ReservationCommand command, Supplier<ReservationResponse> reservationOperation) {
        return reservationOperation.get();
    }

    @Override
    public ReservationDetailResponse getReservation(Long userId, Long reservationId) {
        return reservationQueryService.getReservation(userId, reservationId);
    }

    @Override
    public List<ReservationResponse> getMyReservations(Long userId) {
        return reservationQueryService.getMyReservations(userId);
    }

    @Override
    public void cancelReservation(Long userId, Long reservationId) {
        reservationCancellationService.cancel(userId, reservationId);
    }

}
