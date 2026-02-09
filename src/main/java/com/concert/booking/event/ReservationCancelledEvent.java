package com.concert.booking.event;

import java.util.List;

public record ReservationCancelledEvent(
        Long reservationId,
        Long userId,
        Long scheduleId,
        List<Long> seatIds,
        Integer totalAmount,
        String reason
) {
}
