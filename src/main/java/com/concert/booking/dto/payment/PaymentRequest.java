package com.concert.booking.dto.payment;

import jakarta.validation.constraints.NotNull;

public record PaymentRequest(
        @NotNull(message = "예매 ID는 필수입니다.")
        Long reservationId
) {
}
