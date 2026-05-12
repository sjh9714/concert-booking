package com.concert.booking.observability;

import com.concert.booking.domain.OutboxEventStatus;
import com.concert.booking.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxMetricsSnapshotTest {

    @Test
    void refresh_updates_cached_gauges_without_querying_on_scrape() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OutboxMetricsSnapshot snapshot = new OutboxMetricsSnapshot(repository, meterRegistry);

        when(repository.countByStatus(OutboxEventStatus.PENDING)).thenReturn(3L);
        when(repository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(2L);
        when(repository.countByStatus(OutboxEventStatus.DEAD)).thenReturn(1L);

        snapshot.refresh();

        verify(repository).countByStatus(OutboxEventStatus.PENDING);
        verify(repository).countByStatus(OutboxEventStatus.FAILED);
        verify(repository).countByStatus(OutboxEventStatus.DEAD);

        clearInvocations(repository);

        assertThat(gauge(meterRegistry, "pending")).isEqualTo(3.0);
        assertThat(gauge(meterRegistry, "failed")).isEqualTo(2.0);
        assertThat(gauge(meterRegistry, "dead")).isEqualTo(1.0);
        verifyNoInteractions(repository);
    }

    private double gauge(SimpleMeterRegistry meterRegistry, String status) {
        return meterRegistry.find("concert.booking.outbox.events")
                .tag("status", status)
                .gauge()
                .value();
    }
}
