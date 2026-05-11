package com.concert.booking.event;

import java.time.LocalDateTime;
import java.util.List;

public record ReservationCreatedEvent(
        Long reservationId,
        Long userId,
        Long scheduleId,
        List<Long> seatIds,
        Integer totalAmount,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
