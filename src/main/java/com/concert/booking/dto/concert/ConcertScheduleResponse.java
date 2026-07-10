package com.concert.booking.dto.concert;

import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.common.util.ApiTime;

import java.time.LocalDate;
import java.time.LocalTime;

public record ConcertScheduleResponse(
        Long id,
        Long concertId,
        LocalDate scheduleDate,
        LocalTime startTime,
        int totalSeats,
        int availableSeats,
        String timeZone
) {
    public static ConcertScheduleResponse from(ConcertSchedule schedule) {
        return new ConcertScheduleResponse(
                schedule.getId(),
                schedule.getConcert().getId(),
                schedule.getScheduleDate(),
                schedule.getStartTime(),
                schedule.getTotalSeats(),
                schedule.getAvailableSeats(),
                ApiTime.ZONE_ID
        );
    }
}
