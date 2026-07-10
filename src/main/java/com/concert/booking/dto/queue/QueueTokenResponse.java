package com.concert.booking.dto.queue;

import java.time.OffsetDateTime;

public record QueueTokenResponse(
        String token,
        Long scheduleId,
        OffsetDateTime expiresAt
) {
}
