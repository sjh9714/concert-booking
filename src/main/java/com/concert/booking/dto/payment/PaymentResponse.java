package com.concert.booking.dto.payment;

import com.concert.booking.domain.Payment;
import com.concert.booking.domain.PaymentStatus;
import com.concert.booking.common.util.ApiTime;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentResponse(
        Long id,
        UUID paymentKey,
        Long reservationId,
        int amount,
        PaymentStatus status,
        OffsetDateTime createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPaymentKey(),
                payment.getReservation().getId(),
                payment.getAmount(),
                payment.getStatus(),
                ApiTime.toOffset(payment.getCreatedAt())
        );
    }
}
