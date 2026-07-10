package com.concert.booking.dto.reservation;

import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.dto.concert.SeatResponse;
import com.concert.booking.common.util.ApiTime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReservationDetailResponse(
        Long id,
        UUID reservationKey,
        ReservationStatus status,
        int totalAmount,
        String concertTitle,
        String artist,
        String venue,
        LocalDate scheduleDate,
        LocalTime startTime,
        String timeZone,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        List<SeatResponse> seats
) {
    public static ReservationDetailResponse from(Reservation reservation, List<SeatResponse> seats) {
        return new ReservationDetailResponse(
                reservation.getId(),
                reservation.getReservationKey(),
                reservation.getStatus(),
                reservation.getTotalAmount(),
                reservation.getSchedule().getConcert().getTitle(),
                reservation.getSchedule().getConcert().getArtist(),
                reservation.getSchedule().getConcert().getVenue(),
                reservation.getSchedule().getScheduleDate(),
                reservation.getSchedule().getStartTime(),
                ApiTime.ZONE_ID,
                ApiTime.toOffset(reservation.getExpiresAt()),
                ApiTime.toOffset(reservation.getCreatedAt()),
                seats
        );
    }
}
