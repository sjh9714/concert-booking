package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.dto.reservation.ReservationDetailResponse;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.service.stock.RedisStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockReservationService implements ReservationService, SeatReservationStrategy {

    private static final long LOCK_WAIT_TIME = 3; // 3초 대기
    private static final long LOCK_LEASE_TIME = 5; // 5초 자동 해제

    private final ReservationOrchestrator reservationOrchestrator;
    private final ReservationQueryService reservationQueryService;
    private final ReservationCancellationService reservationCancellationService;
    private final RedissonClient redissonClient;
    private final RedisStockService redisStockService;

    @Override
    public ReservationResponse reserve(Long userId, ReservationRequest request, String idempotencyKey) {
        return reservationOrchestrator.reserve(userId, request, idempotencyKey, this);
    }

    @Override
    public ReservationCreationMode creationMode() {
        return ReservationCreationMode.DISTRIBUTED;
    }

    @Override
    public ReservationResponse execute(ReservationCommand command, Supplier<ReservationResponse> reservationOperation) {
        RedisStockService.StockLease stockLease =
                redisStockService.tryAcquire(command.request().scheduleId(), command.sortedSeatIds().size());

        try {
            List<RLock> locks = command.sortedSeatIds().stream()
                    .map(id -> redissonClient.getLock("lock:seat:" + id))
                    .toList();
            RLock multiLock = redissonClient.getMultiLock(locks.toArray(new RLock[0]));

            try {
                if (!multiLock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS)) {
                    throw new SeatNotAvailableException("좌석 잠금 획득에 실패했습니다. 다시 시도해주세요.");
                }

                ReservationResponse response = reservationOperation.get();
                stockLease.complete();
                return response;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SeatNotAvailableException("좌석 예매 중 인터럽트가 발생했습니다.");
            } finally {
                if (multiLock.isHeldByCurrentThread()) {
                    try {
                        multiLock.unlock();
                    } catch (RuntimeException e) {
                        log.warn("분산 좌석 락 해제에 실패했습니다.", e);
                    }
                }
            }
        } catch (RuntimeException | Error e) {
            stockLease.restoreOnce();
            throw e;
        }
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
