package com.concert.booking.observability;

import com.concert.booking.common.exception.BadRequestException;
import com.concert.booking.common.exception.ConflictException;
import com.concert.booking.common.exception.InvalidQueueTokenException;
import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.common.exception.SoldOutException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class BookingMetrics {

    private static final String NONE = "none";

    private final MeterRegistry meterRegistry;

    public <T> T recordReservation(Supplier<T> operation) {
        increment("concert.booking.reservation.attempts");
        long startedAt = System.nanoTime();

        try {
            T result = operation.get();
            increment("concert.booking.reservation.success");
            recordReservationLatency(startedAt, "success", NONE);
            return result;
        } catch (RuntimeException | Error e) {
            String reason = reservationFailureReason(e);
            counter("concert.booking.reservation.failures", "reason", reason).increment();
            recordReservationLatency(startedAt, "failure", reason);
            throw e;
        }
    }

    public void recordQueueTokenIssued() {
        increment("concert.booking.queue.token.issued");
    }

    public void recordQueueTokenValidationFailure() {
        increment("concert.booking.queue.token.validation.failures");
    }

    public void recordQueueTokenInFlightConflict() {
        increment("concert.booking.queue.token.inflight.conflicts");
    }

    public long startOutboxPublish() {
        return System.nanoTime();
    }

    public void recordOutboxPublished(long startedAt) {
        increment("concert.booking.outbox.published");
        recordOutboxLatency(startedAt, "published");
    }

    public void recordOutboxFailed(long startedAt) {
        increment("concert.booking.outbox.failed");
        recordOutboxLatency(startedAt, "failed");
    }

    public void recordOutboxDead(long startedAt) {
        increment("concert.booking.outbox.dead");
        recordOutboxLatency(startedAt, "dead");
    }

    public void recordStockReconciliationRun(boolean repair) {
        counter("concert.booking.stock.reconciliation.runs", "repair", String.valueOf(repair)).increment();
    }

    public void recordStockReconciliationMismatch() {
        increment("concert.booking.stock.reconciliation.mismatches");
    }

    public void recordStockReconciliationRepair() {
        increment("concert.booking.stock.reconciliation.repairs");
    }

    public String reservationFailureReason(Throwable throwable) {
        if (hasCause(throwable, InvalidQueueTokenException.class)) {
            return "queue_token_invalid";
        }
        if (hasCause(throwable, SoldOutException.class)) {
            return "sold_out";
        }
        SeatNotAvailableException seatException = findCause(throwable, SeatNotAvailableException.class);
        if (seatException != null) {
            String message = seatException.getMessage();
            if (message != null && (message.contains("잠금") || message.toLowerCase().contains("lock"))) {
                return "lock_failure";
            }
            return "seat_not_available";
        }
        if (hasCause(throwable, ObjectOptimisticLockingFailureException.class)) {
            return "lock_failure";
        }
        if (hasCause(throwable, ConflictException.class)) {
            return "idempotency_conflict";
        }
        if (hasCause(throwable, BadRequestException.class)) {
            return "bad_request";
        }
        return "unknown";
    }

    private void recordReservationLatency(long startedAt, String outcome, String reason) {
        timer("concert.booking.reservation.latency", "outcome", outcome, "reason", reason)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    private void recordOutboxLatency(long startedAt, String outcome) {
        timer("concert.booking.outbox.publish.latency", "outcome", outcome)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    private void increment(String name) {
        counter(name).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(meterRegistry);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        return findCause(throwable, type) != null;
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
