package com.concert.booking.dto.queue;

public record QueuePositionResponse(
        Long position,
        Long totalWaiting,
        String estimatedWaitTime
) {
}
