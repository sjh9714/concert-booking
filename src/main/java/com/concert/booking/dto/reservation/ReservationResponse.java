package com.concert.booking.dto.reservation;

import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.common.util.ApiTime;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ReservationResponse(
        Long id,
        UUID reservationKey,
        ReservationStatus status,
        int totalAmount,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt
) {
    public ReservationResponse(Long id, UUID reservationKey, ReservationStatus status, int totalAmount,
                               LocalDateTime expiresAt, LocalDateTime createdAt) {
        this(id, reservationKey, status, totalAmount,
                ApiTime.toOffset(expiresAt), ApiTime.toOffset(createdAt));
    }

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getReservationKey(),
                reservation.getStatus(),
                reservation.getTotalAmount(),
                ApiTime.toOffset(reservation.getExpiresAt()),
                ApiTime.toOffset(reservation.getCreatedAt())
        );
    }
}
