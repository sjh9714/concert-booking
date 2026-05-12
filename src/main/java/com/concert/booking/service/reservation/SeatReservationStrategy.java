package com.concert.booking.service.reservation;

import com.concert.booking.dto.reservation.ReservationResponse;

import java.util.function.Supplier;

public interface SeatReservationStrategy {

    ReservationCreationMode creationMode();

    ReservationResponse execute(ReservationCommand command, Supplier<ReservationResponse> reservationOperation);
}
