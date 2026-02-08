package com.concert.booking.common.exception;

import org.springframework.http.HttpStatus;

public class PaymentException extends BusinessException {

    public PaymentException(String message) {
        super(HttpStatus.BAD_REQUEST, "PAYMENT_ERROR", message);
    }
}
