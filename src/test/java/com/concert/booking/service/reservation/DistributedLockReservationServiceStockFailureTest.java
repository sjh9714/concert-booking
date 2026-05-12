package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributedLockReservationServiceStockFailureTest {

    @Mock private ReservationOrchestrator reservationOrchestrator;
    @Mock private ReservationQueryService reservationQueryService;
    @Mock private ReservationCancellationService reservationCancellationService;
    @Mock private RedissonClient redissonClient;
    @Mock private RedisStockService redisStockService;
    @Mock private RedisStockService.StockLease stockLease;
    @Mock private RLock seatLock;
    @Mock private RLock multiLock;

    private DistributedLockReservationService service;

    @BeforeEach
    void setUp() {
        service = new DistributedLockReservationService(
                reservationOrchestrator,
                reservationQueryService,
                reservationCancellationService,
                redissonClient,
                redisStockService
        );
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("Redis decrement 후 lock 획득 실패 시 stock을 복원하고 lock을 해제하지 않는다")
    void lock_acquisition_failure_restores_stock_once() throws Exception {
        givenStockAndLock();
        when(multiLock.tryLock(3, 5, TimeUnit.SECONDS)).thenReturn(false);
        when(multiLock.isHeldByCurrentThread()).thenReturn(false);

        assertThatThrownBy(() -> service.execute(command(), successfulOperation()))
                .isInstanceOf(SeatNotAvailableException.class);

        verify(stockLease).restoreOnce();
        verify(multiLock, never()).unlock();
    }

    @Test
    @DisplayName("Redis decrement 후 DB 트랜잭션 실패 시 stock을 복원하고 lock을 해제한다")
    void db_transaction_failure_restores_stock_and_unlocks() throws Exception {
        givenStockAndLock();
        when(multiLock.tryLock(3, 5, TimeUnit.SECONDS)).thenReturn(true);
        when(multiLock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> service.execute(command(), failingOperation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db fail");

        verify(stockLease).restoreOnce();
        verify(multiLock).unlock();
    }

    @Test
    @DisplayName("tryLock InterruptedException은 interrupt flag를 복원하고 stock을 복원한다")
    void interrupted_try_lock_restores_interrupt_flag_and_stock() throws Exception {
        givenStockAndLock();
        when(multiLock.tryLock(3, 5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));
        when(multiLock.isHeldByCurrentThread()).thenReturn(false);

        assertThatThrownBy(() -> service.execute(command(), successfulOperation()))
                .isInstanceOf(SeatNotAvailableException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(stockLease).restoreOnce();
        verify(multiLock, never()).unlock();
    }

    @Test
    @DisplayName("DB 트랜잭션 성공 후에는 stock lease를 완료하고 복원하지 않는다")
    void successful_operation_completes_stock_lease() throws Exception {
        givenStockAndLock();
        when(multiLock.tryLock(3, 5, TimeUnit.SECONDS)).thenReturn(true);
        when(multiLock.isHeldByCurrentThread()).thenReturn(true);

        ReservationResponse response = service.execute(command(), successfulOperation());

        assertThat(response.id()).isEqualTo(1L);
        verify(stockLease).complete();
        verify(stockLease, never()).restoreOnce();
        verify(multiLock).unlock();
    }

    private void givenStockAndLock() {
        when(redisStockService.tryAcquire(1L, 1)).thenReturn(stockLease);
        when(redissonClient.getLock("lock:seat:10")).thenReturn(seatLock);
        when(redissonClient.getMultiLock(any(RLock[].class))).thenReturn(multiLock);
    }

    private ReservationCommand command() {
        return new ReservationCommand(
                1L,
                new ReservationRequest(1L, List.of(10L), "queue-token"),
                List.of(10L),
                10L
        );
    }

    private Supplier<ReservationResponse> successfulOperation() {
        return () -> new ReservationResponse(
                1L,
                UUID.randomUUID(),
                ReservationStatus.PENDING,
                100000,
                LocalDateTime.now().plusMinutes(5),
                LocalDateTime.now()
        );
    }

    private Supplier<ReservationResponse> failingOperation() {
        return () -> {
            throw new IllegalStateException("db fail");
        };
    }
}
