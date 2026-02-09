package com.concert.booking.dto.queue;

public record QueueTokenResponse(
        String token,
        Long scheduleId
) {
}
