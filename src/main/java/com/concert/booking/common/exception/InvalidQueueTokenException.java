package com.concert.booking.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidQueueTokenException extends BusinessException {

    public InvalidQueueTokenException(String message) {
        super(HttpStatus.UNAUTHORIZED, "INVALID_QUEUE_TOKEN", message);
    }
}
