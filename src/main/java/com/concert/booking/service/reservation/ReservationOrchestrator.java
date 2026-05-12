package com.concert.booking.service.reservation;

import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.service.queue.QueueTokenGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReservationOrchestrator {

    private final QueueTokenGuard queueTokenGuard;
    private final ReservationIdempotencyService reservationIdempotencyService;
    private final ReservationCreationService reservationCreationService;
    private final TransactionTemplate transactionTemplate;

    public ReservationResponse reserve(
            Long userId,
            ReservationRequest request,
            String idempotencyKey,
            SeatReservationStrategy strategy
    ) {
        ReservationIdempotencyService.ReservationClaim claim =
                reservationIdempotencyService.claimOrReplay(userId, request.scheduleId(), idempotencyKey, request.seatIds());
        if (claim.replay()) {
            return claim.replayResponse();
        }

        QueueTokenGuard.TokenLease tokenLease = null;
        boolean tokenConsumed = false;
        boolean reservationCommitted = false;
        try {
            tokenLease = queueTokenGuard.acquire(userId, request.scheduleId(), request.queueToken());

            List<Long> sortedSeatIds = request.seatIds().stream().sorted().toList();
            ReservationCommand command = new ReservationCommand(userId, request, sortedSeatIds, claim.claimId());

            ReservationResponse response = strategy.execute(
                    command,
                    () -> transactionTemplate.execute(status ->
                            reservationCreationService.create(command, strategy.creationMode()))
            );

            reservationCommitted = true;
            queueTokenGuard.consume(tokenLease);
            tokenConsumed = true;

            return response;
        } catch (RuntimeException | Error e) {
            if (tokenLease != null && !tokenConsumed) {
                queueTokenGuard.release(tokenLease);
            }
            if (!reservationCommitted) {
                reservationIdempotencyService.deleteProcessingClaim(claim.claimId());
            }
            throw e;
        }
    }
}
