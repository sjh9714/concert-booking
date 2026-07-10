package com.concert.booking.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "queue")
public class QueueProperties {

    private int entryThreshold = 100;
    private Duration tokenTtl = Duration.ofMinutes(5);
    private Duration expiryTombstoneTtl = Duration.ofMinutes(10);
    private Duration sseTimeout = Duration.ofMinutes(5);
    private Duration updateInterval = Duration.ofSeconds(1);
    private Duration heartbeatInterval = Duration.ofSeconds(15);
}
