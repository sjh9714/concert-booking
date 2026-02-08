package com.concert.booking.dto.reservation;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReservationRequest(
        @NotNull(message = "스케줄 ID는 필수입니다.")
        Long scheduleId,

        @NotEmpty(message = "좌석을 선택해주세요.")
        @Size(max = 4, message = "최대 4석까지 예매 가능합니다.")
        List<Long> seatIds
) {
}
