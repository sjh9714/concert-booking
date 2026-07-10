package com.concert.booking.dto.queue;

import java.time.OffsetDateTime;

public record QueuePositionResponse(
        QueueStatus status,
        Long position,
        Long totalWaiting,
        OffsetDateTime serverTime
) {
}
