package com.concert.booking.service.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DltReplayService {

    private final ConsumerFactory<String, Object> consumerFactory;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.dlt-replay.poll-timeout-ms:500}")
    private long pollTimeoutMs;

    @Value("${kafka.dlt-replay.max-wait-ms:5000}")
    private long maxWaitMs;

    public ReplayResult replay(String dltTopic, int limit) {
        if (dltTopic == null || dltTopic.isBlank()) {
            throw new IllegalArgumentException("DLT topic is required.");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Replay limit must be greater than 0.");
        }

        String groupId = "dlt-replay-" + UUID.randomUUID();
        int replayed = 0;
        int failed = 0;

        try (Consumer<String, Object> consumer = replayConsumer(groupId)) {
            consumer.subscribe(List.of(dltTopic));
            long deadline = System.currentTimeMillis() + maxWaitMs;

            while (replayed + failed < limit && System.currentTimeMillis() < deadline) {
                var records = consumer.poll(Duration.ofMillis(pollTimeoutMs));
                if (records.isEmpty()) {
                    continue;
                }

                for (ConsumerRecord<String, Object> record : records) {
                    if (replayed + failed >= limit) {
                        break;
                    }
                    try {
                        String originalTopic = originalTopic(record, dltTopic);
                        kafkaTemplate.send(originalTopic, record.key(), record.value()).get(5, TimeUnit.SECONDS);
                        replayed++;
                        log.info("DLT replay success: dltTopic={}, originalTopic={}, key={}",
                                dltTopic, originalTopic, record.key());
                    } catch (Exception e) {
                        failed++;
                        log.warn("DLT replay failed: dltTopic={}, key={}", dltTopic, record.key(), e);
                    }
                }
            }
        }

        return new ReplayResult(dltTopic, replayed, failed);
    }

    private Consumer<String, Object> replayConsumer(String groupId) {
        Map<String, Object> props = new HashMap<>(consumerFactory.getConfigurationProperties());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<String, Object>(props).createConsumer();
    }

    private String originalTopic(ConsumerRecord<String, Object> record, String dltTopic) {
        Header originalTopicHeader = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (originalTopicHeader != null) {
            return new String(originalTopicHeader.value(), StandardCharsets.UTF_8);
        }
        if (dltTopic.endsWith(".DLT")) {
            return dltTopic.substring(0, dltTopic.length() - 4);
        }
        throw new IllegalArgumentException("Cannot resolve original topic for DLT topic: " + dltTopic);
    }

    public record ReplayResult(String dltTopic, int replayedCount, int failedCount) {
    }
}
