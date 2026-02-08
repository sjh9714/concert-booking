package com.concert.booking.dto.reservation;

import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.dto.concert.SeatResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReservationDetailResponse(
        Long id,
        UUID reservationKey,
        ReservationStatus status,
        int totalAmount,
        String concertTitle,
        String venue,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        List<SeatResponse> seats
) {
    public static ReservationDetailResponse from(Reservation reservation, List<SeatResponse> seats) {
        return new ReservationDetailResponse(
                reservation.getId(),
                reservation.getReservationKey(),
                reservation.getStatus(),
                reservation.getTotalAmount(),
                reservation.getSchedule().getConcert().getTitle(),
                reservation.getSchedule().getConcert().getVenue(),
                reservation.getExpiresAt(),
                reservation.getCreatedAt(),
                seats
        );
    }
}
