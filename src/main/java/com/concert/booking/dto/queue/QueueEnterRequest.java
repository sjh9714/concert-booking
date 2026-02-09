package com.concert.booking.dto.queue;

import jakarta.validation.constraints.NotNull;

public record QueueEnterRequest(
        @NotNull(message = "스케줄 ID는 필수입니다.")
        Long scheduleId
) {
}
