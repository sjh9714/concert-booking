package com.concert.booking.service.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private final OutboxRelayService outboxRelayService;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:5000}")
    @SchedulerLock(name = "relayOutboxEvents", lockAtLeastFor = "1s", lockAtMostFor = "30s")
    public void relayOutboxEvents() {
        int publishedCount = outboxRelayService.publishPendingBatch();
        if (publishedCount > 0) {
            log.info("Outbox relay published {} event(s)", publishedCount);
        }
    }
}
