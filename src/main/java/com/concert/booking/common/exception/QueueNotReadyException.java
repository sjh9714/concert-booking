package com.concert.booking.common.exception;

import org.springframework.http.HttpStatus;

public class QueueNotReadyException extends BusinessException {

    public QueueNotReadyException(String message) {
        super(HttpStatus.FORBIDDEN, "QUEUE_NOT_READY", message);
    }
}
