package com.concert.booking.common.exception;

import org.springframework.http.HttpStatus;

public class ReservationNotFoundException extends BusinessException {

    public ReservationNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", message);
    }
}
