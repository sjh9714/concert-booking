package com.concert.booking.event;

import java.time.LocalDateTime;

public record ReservationCompletedEvent(
        Long reservationId,
        Long userId,
        Long scheduleId,
        Integer totalAmount,
        LocalDateTime confirmedAt
) {
}
