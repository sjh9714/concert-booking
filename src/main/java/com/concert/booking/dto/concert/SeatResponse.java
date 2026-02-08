package com.concert.booking.dto.concert;

import com.concert.booking.domain.Seat;
import com.concert.booking.domain.SeatStatus;

public record SeatResponse(
        Long id,
        String section,
        int rowNumber,
        int seatNumber,
        int price,
        SeatStatus status
) {
    public static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getSection(),
                seat.getRowNumber(),
                seat.getSeatNumber(),
                seat.getPrice(),
                seat.getStatus()
        );
    }
}
