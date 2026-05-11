package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import com.concert.booking.service.outbox.OutboxEventService;
import com.concert.booking.service.queue.QueueTokenGuard;
import com.concert.booking.service.stock.RedisStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributedLockReservationServiceStockFailureTest {

    @Mock private UserRepository userRepository;
    @Mock private ConcertScheduleRepository concertScheduleRepository;
    @Mock private SeatRepository seatRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationSeatRepository reservationSeatRepository;
    @Mock private RedissonClient redissonClient;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private QueueTokenGuard queueTokenGuard;
    @Mock private ReservationIdempotencyService reservationIdempotencyService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ReservationCancellationService reservationCancellationService;
    @Mock private OutboxEventService outboxEventService;
    @Mock private RedisStockService redisStockService;
    @Mock private RedisStockService.StockLease stockLease;
    @Mock private RLock seatLock;
    @Mock private RLock multiLock;

    private DistributedLockReservationService service;
    private QueueTokenGuard.TokenLease tokenLease;

    @BeforeEach
    void setUp() {
        service = new DistributedLockReservationService(
                userRepository,
                concertScheduleRepository,
                seatRepository,
                reservationRepository,
                reservationSeatRepository,
                redissonClient,
                redisTemplate,
                queueTokenGuard,
                reservationIdempotencyService,
                transactionTemplate,
                reservationCancellationService,
                outboxEventService,
                redisStockService
        );
        tokenLease = new QueueTokenGuard.TokenLease("token-key", "inflight-key");
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("Redis decrement 후 lock 획득 실패 시 stock을 복원하고 token/claim을 정리한다")
    void lock_acquisition_failure_restores_stock_once() throws Exception {
        givenClaimTokenStockAndLock("idem-lock-fail");
        when(multiLock.tryLock(3, 5, TimeUnit.SECONDS)).thenReturn(false);
        when(multiLock.isHeldByCurrentThread()).thenReturn(false);

        assertThatThrownBy(() -> service.reserve(1L, request(), "idem-lock-fail"))
                .isInstanceOf(SeatNotAvailableException.class);

        verify(stockLease).restoreOnce();
        verify(multiLock, never()).unlock();
        verify(queueTokenGuard).release(tokenLease);
        verify(reservationIdempotencyService).deleteProcessingClaim(10L);
    }

    @Test
    @DisplayName("Redis decrement 후 DB 트랜잭션 실패 시 stock을 복원하고 lock을 해제한다")
    void db_transaction_failure_restores_stock_and_unlocks() throws Exception {
        givenClaimTokenStockAndLock("idem-db-fail");
        when(multiLock.tryLock(3, 5, TimeUnit.SECONDS)).thenReturn(true);
        when(transactionTemplate.execute(any())).thenThrow(new IllegalStateException("db fail"));
        when(multiLock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> service.reserve(1L, request(), "idem-db-fail"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db fail");

        verify(stockLease).restoreOnce();
        verify(multiLock).unlock();
        verify(queueTokenGuard).release(tokenLease);
        verify(reservationIdempotencyService).deleteProcessingClaim(10L);
    }

    @Test
    @DisplayName("tryLock InterruptedException은 interrupt flag를 복원하고 stock을 복원한다")
    void interrupted_try_lock_restores_interrupt_flag_and_stock() throws Exception {
        givenClaimTokenStockAndLock("idem-interrupted");
        when(multiLock.tryLock(3, 5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));
        when(multiLock.isHeldByCurrentThread()).thenReturn(false);

        assertThatThrownBy(() -> service.reserve(1L, request(), "idem-interrupted"))
                .isInstanceOf(SeatNotAvailableException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(stockLease).restoreOnce();
        verify(multiLock, never()).unlock();
        verify(queueTokenGuard).release(tokenLease);
        verify(reservationIdempotencyService).deleteProcessingClaim(10L);
    }

    private void givenClaimTokenStockAndLock(String idempotencyKey) {
        when(reservationIdempotencyService.claimOrReplay(1L, 1L, idempotencyKey, List.of(10L)))
                .thenReturn(ReservationIdempotencyService.ReservationClaim.owner(10L, "hash"));
        when(queueTokenGuard.acquire(1L, 1L, "queue-token")).thenReturn(tokenLease);
        when(redisStockService.tryAcquire(1L, 1)).thenReturn(stockLease);
        when(redissonClient.getLock("lock:seat:10")).thenReturn(seatLock);
        when(redissonClient.getMultiLock(any(RLock[].class))).thenReturn(multiLock);
    }

    private ReservationRequest request() {
        return new ReservationRequest(1L, List.of(10L), "queue-token");
    }
}
