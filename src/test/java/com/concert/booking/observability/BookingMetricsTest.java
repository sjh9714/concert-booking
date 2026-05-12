package com.concert.booking.observability;

import com.concert.booking.common.exception.ConflictException;
import com.concert.booking.common.exception.InvalidQueueTokenException;
import com.concert.booking.common.exception.SeatNotAvailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BookingMetrics bookingMetrics = new BookingMetrics(meterRegistry);

    @Test
    void recordReservation_counts_attempt_success_and_latency() {
        String result = bookingMetrics.recordReservation(() -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(counter("concert.booking.reservation.attempts")).isEqualTo(1.0);
        assertThat(counter("concert.booking.reservation.success")).isEqualTo(1.0);
        assertThat(meterRegistry.find("concert.booking.reservation.latency")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void recordReservation_counts_failure_by_reason() {
        assertThatThrownBy(() -> bookingMetrics.recordReservation(() -> {
            throw new InvalidQueueTokenException("유효하지 않은 입장 토큰입니다.");
        })).isInstanceOf(InvalidQueueTokenException.class);

        assertThat(counter("concert.booking.reservation.failures", "reason", "queue_token_invalid"))
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("concert.booking.reservation.latency")
                .tag("outcome", "failure")
                .tag("reason", "queue_token_invalid")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void reservationFailureReason_classifies_core_failures() {
        assertThat(bookingMetrics.reservationFailureReason(new SeatNotAvailableException("좌석 잠금 획득에 실패했습니다.")))
                .isEqualTo("lock_failure");
        assertThat(bookingMetrics.reservationFailureReason(new SeatNotAvailableException("선택한 좌석 중 이미 예매된 좌석이 있습니다.")))
                .isEqualTo("seat_not_available");
        assertThat(bookingMetrics.reservationFailureReason(new ConflictException("같은 Idempotency-Key로 다른 좌석 예매 요청을 보낼 수 없습니다.")))
                .isEqualTo("idempotency_conflict");
    }

    @Test
    void records_queue_outbox_and_reconciliation_metrics() {
        long startedAt = bookingMetrics.startOutboxPublish();

        bookingMetrics.recordQueueTokenIssued();
        bookingMetrics.recordQueueTokenValidationFailure();
        bookingMetrics.recordQueueTokenInFlightConflict();
        bookingMetrics.recordOutboxPublished(startedAt);
        bookingMetrics.recordStockReconciliationRun(true);
        bookingMetrics.recordStockReconciliationMismatch();
        bookingMetrics.recordStockReconciliationRepair();

        assertThat(counter("concert.booking.queue.token.issued")).isEqualTo(1.0);
        assertThat(counter("concert.booking.queue.token.validation.failures")).isEqualTo(1.0);
        assertThat(counter("concert.booking.queue.token.inflight.conflicts")).isEqualTo(1.0);
        assertThat(counter("concert.booking.outbox.published")).isEqualTo(1.0);
        assertThat(counter("concert.booking.stock.reconciliation.runs", "repair", "true")).isEqualTo(1.0);
        assertThat(counter("concert.booking.stock.reconciliation.mismatches")).isEqualTo(1.0);
        assertThat(counter("concert.booking.stock.reconciliation.repairs")).isEqualTo(1.0);
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter().count();
    }
}
