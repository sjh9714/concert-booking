package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.ForbiddenException;
import com.concert.booking.common.exception.ReservationNotFoundException;
import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.domain.*;
import com.concert.booking.dto.concert.SeatResponse;
import com.concert.booking.dto.reservation.ReservationDetailResponse;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.repository.*;
import com.concert.booking.service.outbox.OutboxEventService;
import com.concert.booking.service.queue.QueueTokenGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptimisticLockReservationService implements ReservationService {

    private static final int HOLD_MINUTES = 5;

    private final UserRepository userRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final QueueTokenGuard queueTokenGuard;
    private final ReservationIdempotencyService reservationIdempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final ReservationCancellationService reservationCancellationService;
    private final OutboxEventService outboxEventService;

    @Override
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2)
    )
    public ReservationResponse reserve(Long userId, ReservationRequest request, String idempotencyKey) {
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

            ReservationResponse response = transactionTemplate.execute(status ->
                    createReservation(userId, request, claim.claimId()));

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
        // 커밋 시 @Version 불일치 → ObjectOptimisticLockingFailureException → @Retryable 재시도
    }

    private ReservationResponse createReservation(Long userId, ReservationRequest request, Long claimId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        ConcertSchedule schedule = concertScheduleRepository.findById(request.scheduleId())
                .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다."));

        // 좌석 ID 정렬
        List<Long> sortedSeatIds = request.seatIds().stream().sorted().toList();

        // 락 없이 좌석 조회 (커밋 시 @Version으로 충돌 감지)
        List<Seat> seats = seatRepository.findAllByScheduleIdAndIdInAndAvailable(
                request.scheduleId(),
                sortedSeatIds);

        // All-or-Nothing
        if (seats.size() != sortedSeatIds.size()) {
            throw new SeatNotAvailableException("선택한 좌석 중 이미 예매된 좌석이 있습니다.");
        }

        // 좌석 HOLD 처리
        seats.forEach(Seat::hold);

        // 총 금액 계산
        int totalAmount = seats.stream().mapToInt(Seat::getPrice).sum();

        // 예매 생성
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(HOLD_MINUTES);
        Reservation reservation = Reservation.create(user, schedule, totalAmount, expiresAt);
        reservationRepository.save(reservation);

        // 예매-좌석 매핑
        for (Seat seat : seats) {
            ReservationSeat rs = ReservationSeat.create(reservation, seat);
            reservationSeatRepository.save(rs);
            reservation.addReservationSeat(rs);
        }

        // 잔여 좌석 감소
        schedule.decreaseAvailableSeats(seats.size());

        reservationIdempotencyService.complete(claimId, reservation);
        outboxEventService.saveReservationCreated(reservation);

        return ReservationResponse.from(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationDetailResponse getReservation(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("예매를 찾을 수 없습니다."));

        if (!reservation.getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인의 예매만 조회할 수 있습니다.");
        }

        List<SeatResponse> seats = reservation.getReservationSeats().stream()
                .map(rs -> SeatResponse.from(rs.getSeat()))
                .toList();

        return ReservationDetailResponse.from(reservation, seats);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationResponse> getMyReservations(Long userId) {
        return reservationRepository.findByUserId(userId).stream()
                .map(ReservationResponse::from)
                .toList();
    }

    @Override
    public void cancelReservation(Long userId, Long reservationId) {
        reservationCancellationService.cancel(userId, reservationId);
    }

}
