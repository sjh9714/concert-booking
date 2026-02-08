package com.concert.booking.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidReservationStateException extends BusinessException {

    public InvalidReservationStateException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_STATE", message);
    }
}
