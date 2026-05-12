package com.concert.booking.integration;

import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.OutboxEvent;
import com.concert.booking.domain.OutboxEventStatus;
import com.concert.booking.domain.OutboxEventType;
import com.concert.booking.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "management.metrics.outbox.gauge-refresh-ms=100",
        "management.metrics.outbox.gauge-initial-delay-ms=0",
        "outbox.relay.enabled=false"
})
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class OutboxMetricsSnapshotSchedulingIntegrationTest {

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Outbox gauge snapshot은 scheduling으로 pending/failed count를 갱신한다")
    void scheduled_refresh_updates_outbox_event_gauges() {
        outboxEventRepository.save(OutboxEvent.create(
                "Reservation",
                1L,
                OutboxEventType.RESERVATION_CREATED,
                "reservation.created",
                """
                        {"reservationId":1,"userId":1,"scheduleId":1,"seatIds":[1],"totalAmount":1000,"expiresAt":"2026-05-12T12:00:00","createdAt":"2026-05-12T11:55:00"}
                        """
        ));

        OutboxEvent failed = OutboxEvent.create(
                "Reservation",
                2L,
                OutboxEventType.RESERVATION_CONFIRMED,
                "reservation.completed",
                """
                        {"reservationId":2,"userId":1,"scheduleId":1,"totalAmount":1000,"confirmedAt":"2026-05-12T12:00:00"}
                        """
        );
        failed.markFailed("kafka down", LocalDateTime.now(), LocalDateTime.now().plusSeconds(1), 5);
        outboxEventRepository.save(failed);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(outboxGauge("pending")).isEqualTo(1.0);
                    assertThat(outboxGauge("failed")).isEqualTo(1.0);
                });
    }

    private double outboxGauge(String status) {
        return meterRegistry.find("concert.booking.outbox.events")
                .tag("status", status)
                .gauge()
                .value();
    }
}
