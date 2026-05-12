package com.concert.booking.observability;

import com.concert.booking.domain.OutboxEventStatus;
import com.concert.booking.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxMetricsSnapshot {

    private final OutboxEventRepository outboxEventRepository;
    private final AtomicLong pendingEvents = new AtomicLong();
    private final AtomicLong failedEvents = new AtomicLong();
    private final AtomicLong deadEvents = new AtomicLong();

    public OutboxMetricsSnapshot(OutboxEventRepository outboxEventRepository, MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        registerGauge(meterRegistry, "pending", pendingEvents);
        registerGauge(meterRegistry, "failed", failedEvents);
        registerGauge(meterRegistry, "dead", deadEvents);
    }

    @Scheduled(
            fixedDelayString = "${management.metrics.outbox.gauge-refresh-ms:30000}",
            initialDelayString = "${management.metrics.outbox.gauge-initial-delay-ms:0}"
    )
    @Transactional(readOnly = true)
    public void refresh() {
        pendingEvents.set(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING));
        failedEvents.set(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED));
        deadEvents.set(outboxEventRepository.countByStatus(OutboxEventStatus.DEAD));
    }

    private void registerGauge(MeterRegistry meterRegistry, String status, AtomicLong value) {
        Gauge.builder("concert.booking.outbox.events", value, AtomicLong::get)
                .tag("status", status)
                .register(meterRegistry);
    }
}
