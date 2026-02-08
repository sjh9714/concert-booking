package com.concert.booking.common.exception;

import org.springframework.http.HttpStatus;

public class SoldOutException extends BusinessException {

    public SoldOutException(String message) {
        super(HttpStatus.CONFLICT, "SOLD_OUT", message);
    }
}
