package com.concert.booking.dto.concert;

import com.concert.booking.domain.ConcertSchedule;

import java.time.LocalDate;
import java.time.LocalTime;

public record ConcertScheduleResponse(
        Long id,
        Long concertId,
        LocalDate scheduleDate,
        LocalTime startTime,
        int totalSeats,
        int availableSeats
) {
    public static ConcertScheduleResponse from(ConcertSchedule schedule) {
        return new ConcertScheduleResponse(
                schedule.getId(),
                schedule.getConcert().getId(),
                schedule.getScheduleDate(),
                schedule.getStartTime(),
                schedule.getTotalSeats(),
                schedule.getAvailableSeats()
        );
    }
}
