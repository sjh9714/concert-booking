package com.concert.booking.config;

import com.concert.booking.common.util.ApiTime;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

@Configuration
public class ApplicationTimeZoneConfig {

    @PostConstruct
    void useDomainTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(ApiTime.ZONE_ID));
    }
}
