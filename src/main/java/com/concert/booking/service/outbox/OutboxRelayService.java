package com.concert.booking.service.outbox;

import com.concert.booking.domain.OutboxEvent;
import com.concert.booking.observability.BookingMetrics;
import com.concert.booking.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final BookingMetrics bookingMetrics;

    @Value("${outbox.relay.batch-size:100}")
    private int defaultBatchSize;

    @Value("${outbox.relay.lock-stale-seconds:60}")
    private long lockStaleSeconds;

    @Value("${outbox.relay.send-timeout-seconds:5}")
    private long sendTimeoutSeconds;

    public int publishPendingBatch() {
        return publishPendingBatch(defaultBatchSize);
    }

    public int publishPendingBatch(int batchSize) {
        List<Long> eventIds = claimPublishableEvents(batchSize);
        int publishedCount = 0;

        for (Long eventId : eventIds) {
            if (publishClaimedEvent(eventId)) {
                publishedCount++;
            }
        }

        return publishedCount;
    }

    private List<Long> claimPublishableEvents(int batchSize) {
        return inRequiresNew(() -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime staleBefore = now.minus(Duration.ofSeconds(lockStaleSeconds));
            List<OutboxEvent> events = outboxEventRepository.findPublishableForUpdate(staleBefore, batchSize);
            events.forEach(event -> event.claim(now));
            return events.stream()
                    .map(OutboxEvent::getId)
                    .collect(Collectors.toList());
        });
    }

    private boolean publishClaimedEvent(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus().name().equals("PUBLISHED")) {
            return false;
        }

        long startedAt = bookingMetrics.startOutboxPublish();
        try {
            Object payload = deserializePayload(event);
            kafkaTemplate.send(event.getTopic(), String.valueOf(event.getAggregateId()), payload)
                    .get(sendTimeoutSeconds, TimeUnit.SECONDS);
            markPublished(eventId);
            bookingMetrics.recordOutboxPublished(startedAt);
            log.info("Outbox event published: id={}, eventType={}, topic={}",
                    event.getId(), event.getEventType(), event.getTopic());
            return true;
        } catch (Exception e) {
            markFailed(eventId, rootMessage(e));
            bookingMetrics.recordOutboxFailed(startedAt);
            log.warn("Outbox event publish failed: id={}, eventType={}, topic={}",
                    event.getId(), event.getEventType(), event.getTopic(), e);
            return false;
        }
    }

    private Object deserializePayload(OutboxEvent event) throws JsonProcessingException {
        return objectMapper.readValue(event.getPayload(), event.getEventType().payloadClass());
    }

    private void markPublished(Long eventId) {
        inRequiresNew(() -> {
            OutboxEvent event = outboxEventRepository.findById(eventId)
                    .orElseThrow(() -> new IllegalStateException("Outbox event not found: " + eventId));
            event.markPublished(LocalDateTime.now());
            return null;
        });
    }

    private void markFailed(Long eventId, String errorMessage) {
        inRequiresNew(() -> {
            OutboxEvent event = outboxEventRepository.findById(eventId)
                    .orElseThrow(() -> new IllegalStateException("Outbox event not found: " + eventId));
            event.markFailed(errorMessage, LocalDateTime.now());
            return null;
        });
    }

    private <T> T inRequiresNew(java.util.function.Supplier<T> supplier) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> supplier.get());
    }

    private String rootMessage(Exception e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getName() : current.getMessage();
    }
}
