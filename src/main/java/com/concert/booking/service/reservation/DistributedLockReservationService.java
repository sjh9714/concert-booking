package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.ForbiddenException;
import com.concert.booking.common.exception.ReservationNotFoundException;
import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.domain.*;
import com.concert.booking.dto.concert.SeatResponse;
import com.concert.booking.dto.reservation.ReservationDetailResponse;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.observability.BookingMetrics;
import com.concert.booking.repository.*;
import com.concert.booking.service.outbox.OutboxEventService;
import com.concert.booking.service.queue.QueueTokenGuard;
import com.concert.booking.service.stock.RedisStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockReservationService implements ReservationService {

    private static final int HOLD_MINUTES = 5;
    private static final int SEAT_HOLD_TTL_SECONDS = 300; // 5분
    private static final long LOCK_WAIT_TIME = 3; // 3초 대기
    private static final long LOCK_LEASE_TIME = 5; // 5초 자동 해제

    private final UserRepository userRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final RedissonClient redissonClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final QueueTokenGuard queueTokenGuard;
    private final ReservationIdempotencyService reservationIdempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final ReservationCancellationService reservationCancellationService;
    private final OutboxEventService outboxEventService;
    private final RedisStockService redisStockService;
    private final BookingMetrics bookingMetrics;

    @Override
    public ReservationResponse reserve(Long userId, ReservationRequest request, String idempotencyKey) {
        return bookingMetrics.recordReservation(() -> reserveInternal(userId, request, idempotencyKey));
    }

    private ReservationResponse reserveInternal(Long userId, ReservationRequest request, String idempotencyKey) {
        ReservationIdempotencyService.ReservationClaim claim =
                reservationIdempotencyService.claimOrReplay(userId, request.scheduleId(), idempotencyKey, request.seatIds());
        if (claim.replay()) {
            return claim.replayResponse();
        }

        QueueTokenGuard.TokenLease tokenLease = null;
        RedisStockService.StockLease stockLease = null;
        boolean tokenConsumed = false;
        boolean reservationCommitted = false;

        // 1단계: Redis 재고 선검증 (atomic DECR)
        int seatCount = request.seatIds().size();

        try {
            tokenLease = queueTokenGuard.acquire(userId, request.scheduleId(), request.queueToken());
            stockLease = redisStockService.tryAcquire(request.scheduleId(), seatCount);

            // 2단계: 좌석별 분산 락 (Redisson MultiLock)
            List<Long> sortedSeatIds = request.seatIds().stream().sorted().toList();

            List<RLock> locks = sortedSeatIds.stream()
                    .map(id -> redissonClient.getLock("lock:seat:" + id))
                    .toList();

            RLock multiLock = redissonClient.getMultiLock(locks.toArray(new RLock[0]));

            try {
                if (!multiLock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS)) {
                    // 락 획득 실패 → 재고 복원
                    stockLease.restoreOnce();
                    throw new SeatNotAvailableException("좌석 잠금 획득에 실패했습니다. 다시 시도해주세요.");
                }

                // 3단계: DB 트랜잭션 (락 내부에서 실행)
                ReservationResponse response;
                try {
                    response = transactionTemplate.execute(status -> {
                        User user = userRepository.findById(userId)
                                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

                        ConcertSchedule schedule = concertScheduleRepository.findByIdForUpdate(request.scheduleId())
                                .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다."));

                        // 락 없는 일반 SELECT + All-or-Nothing 검증
                        List<Seat> seats = seatRepository.findAllByScheduleIdAndIdInAndAvailable(
                                request.scheduleId(),
                                sortedSeatIds);
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

                        reservationIdempotencyService.complete(claim.claimId(), reservation);
                        outboxEventService.saveReservationCreated(reservation);

                        // Redis 좌석 임시 점유 (TTL 5분)
                        for (Seat seat : seats) {
                            redisTemplate.opsForValue().set(
                                    RedisKeyUtil.seatHoldKey(seat.getId()),
                                    String.valueOf(reservation.getId()),
                                    SEAT_HOLD_TTL_SECONDS, TimeUnit.SECONDS
                            );
                        }

                        return ReservationResponse.from(reservation);
                    });

                } catch (RuntimeException | Error e) {
                    // DB 트랜잭션 실패 → 재고 복원
                    stockLease.restoreOnce();
                    throw e;
                }

                reservationCommitted = true;
                stockLease.complete();

                // 토큰 소비 (1회 사용)
                queueTokenGuard.consume(tokenLease);
                tokenConsumed = true;

                return response;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stockLease.restoreOnce();
                throw new SeatNotAvailableException("좌석 예매 중 인터럽트가 발생했습니다.");
            } finally {
                if (multiLock.isHeldByCurrentThread()) {
                    multiLock.unlock();
                }
            }

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
