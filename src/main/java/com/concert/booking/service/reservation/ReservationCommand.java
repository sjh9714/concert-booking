package com.concert.booking.service.reservation;

import com.concert.booking.dto.reservation.ReservationRequest;

import java.util.List;

public record ReservationCommand(
        Long userId,
        ReservationRequest request,
        List<Long> sortedSeatIds,
        Long claimId
) {
}
