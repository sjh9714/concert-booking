package com.concert.booking.service.outbox;

import com.concert.booking.domain.OutboxEvent;
import com.concert.booking.domain.OutboxEventStatus;
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

    @Value("${outbox.relay.batch-size:100}")
    private int defaultBatchSize;

    @Value("${outbox.relay.lock-stale-seconds:60}")
    private long lockStaleSeconds;

    @Value("${outbox.relay.send-timeout-seconds:5}")
    private long sendTimeoutSeconds;

    @Value("${outbox.relay.max-retry-count:5}")
    private int maxRetryCount;

    @Value("${outbox.relay.initial-backoff-ms:1000}")
    private long initialBackoffMs;

    @Value("${outbox.relay.backoff-multiplier:2}")
    private double backoffMultiplier;

    @Value("${outbox.relay.max-backoff-ms:60000}")
    private long maxBackoffMs;

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
            List<OutboxEvent> events = outboxEventRepository.findPublishableForUpdate(now, staleBefore, batchSize);
            events.forEach(event -> event.claim(now));
            return events.stream()
                    .map(OutboxEvent::getId)
                    .collect(Collectors.toList());
        });
    }

    private boolean publishClaimedEvent(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null
                || event.getStatus() == OutboxEventStatus.PUBLISHED
                || event.getStatus() == OutboxEventStatus.DEAD) {
            return false;
        }

        try {
            Object payload = deserializePayload(event);
            kafkaTemplate.send(event.getTopic(), String.valueOf(event.getAggregateId()), payload)
                    .get(sendTimeoutSeconds, TimeUnit.SECONDS);
            markPublished(eventId);
            log.info("Outbox event published: id={}, eventType={}, topic={}",
                    event.getId(), event.getEventType(), event.getTopic());
            return true;
        } catch (Exception e) {
            markFailed(eventId, rootMessage(e));
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
            LocalDateTime now = LocalDateTime.now();
            int nextRetryCount = event.getRetryCount() + 1;
            LocalDateTime nextAttemptAt = now.plus(Duration.ofMillis(backoffMillis(nextRetryCount)));
            event.markFailed(errorMessage, now, nextAttemptAt, effectiveMaxRetryCount());
            return null;
        });
    }

    private int effectiveMaxRetryCount() {
        return Math.max(1, maxRetryCount);
    }

    private long backoffMillis(int retryCountAfterFailure) {
        long initial = Math.max(0, initialBackoffMs);
        long max = Math.max(initial, maxBackoffMs);
        double multiplier = Math.max(1.0, backoffMultiplier);
        double factor = Math.pow(multiplier, Math.max(0, retryCountAfterFailure - 1));
        double delay = initial * factor;
        if (delay >= max) {
            return max;
        }
        return Math.max(0, (long) delay);
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
