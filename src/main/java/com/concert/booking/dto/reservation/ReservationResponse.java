package com.concert.booking.dto.reservation;

import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReservationResponse(
        Long id,
        UUID reservationKey,
        ReservationStatus status,
        int totalAmount,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getReservationKey(),
                reservation.getStatus(),
                reservation.getTotalAmount(),
                reservation.getExpiresAt(),
                reservation.getCreatedAt()
        );
    }
}
