package com.concert.booking.dto.reservation;

import com.concert.booking.common.util.ApiTime;
import com.concert.booking.domain.PaymentStatus;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.domain.Seat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReservationSummaryResponse(
        Long id,
        UUID reservationKey,
        ReservationStatus status,
        int totalAmount,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        String concertTitle,
        String artist,
        String venue,
        LocalDate scheduleDate,
        LocalTime startTime,
        String timeZone,
        List<String> seatLabels,
        PaymentStatus paymentStatus
) {
    public static ReservationSummaryResponse from(Reservation reservation, PaymentStatus paymentStatus) {
        return new ReservationSummaryResponse(
                reservation.getId(),
                reservation.getReservationKey(),
                reservation.getStatus(),
                reservation.getTotalAmount(),
                ApiTime.toOffset(reservation.getExpiresAt()),
                ApiTime.toOffset(reservation.getCreatedAt()),
                reservation.getSchedule().getConcert().getTitle(),
                reservation.getSchedule().getConcert().getArtist(),
                reservation.getSchedule().getConcert().getVenue(),
                reservation.getSchedule().getScheduleDate(),
                reservation.getSchedule().getStartTime(),
                ApiTime.ZONE_ID,
                reservation.getReservationSeats().stream()
                        .map(item -> label(item.getSeat()))
                        .sorted()
                        .toList(),
                paymentStatus
        );
    }

    private static String label(Seat seat) {
        return seat.getSection() + " " + seat.getRowNumber() + "열 " + seat.getSeatNumber() + "번";
    }
}
