package com.concert.booking.common.exception;

import org.springframework.http.HttpStatus;

public class SeatNotAvailableException extends BusinessException {

    public SeatNotAvailableException(String message) {
        super(HttpStatus.CONFLICT, "SEAT_NOT_AVAILABLE", message);
    }
}
