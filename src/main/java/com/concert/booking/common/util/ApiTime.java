package com.concert.booking.common.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public final class ApiTime {

    public static final String ZONE_ID = "Asia/Seoul";
    private static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    private ApiTime() {
    }

    public static OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
    }
}
