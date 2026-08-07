package com.concert.booking.dto.concert;

import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 공연 목록·상세 응답.
 *
 * <p>회차 요약을 함께 담는다. 예매 목록에서 사람이 고르려면 <b>언제 하는지</b>와
 * <b>자리가 남았는지</b>를 알아야 하는데, 전에는 제목·장소·아티스트만 내려가서
 * 목록만 보고는 아무것도 고를 수 없었다.
 *
 * <p>회차가 없는 공연도 있을 수 있으므로 날짜와 최저가는 null을 허용한다.
 */
public record ConcertResponse(
        Long id,
        String title,
        String description,
        String venue,
        String artist,
        /** 가장 빠른 회차 날짜. 회차가 없으면 null */
        LocalDate nextScheduleDate,
        /** 마지막 회차 날짜. 하루짜리 공연이면 nextScheduleDate와 같다 */
        LocalDate lastScheduleDate,
        int scheduleCount,
        /** 전 회차 잔여 좌석 합계 */
        int availableSeats,
        int totalSeats
) {
    public static ConcertResponse from(Concert concert) {
        return from(concert, List.of());
    }

    public static ConcertResponse from(Concert concert, List<ConcertSchedule> schedules) {
        List<LocalDate> dates = schedules.stream()
                .map(ConcertSchedule::getScheduleDate)
                .sorted(Comparator.naturalOrder())
                .toList();

        return new ConcertResponse(
                concert.getId(),
                concert.getTitle(),
                concert.getDescription(),
                concert.getVenue(),
                concert.getArtist(),
                dates.isEmpty() ? null : dates.get(0),
                dates.isEmpty() ? null : dates.get(dates.size() - 1),
                schedules.size(),
                schedules.stream().mapToInt(ConcertSchedule::getAvailableSeats).sum(),
                schedules.stream().mapToInt(ConcertSchedule::getTotalSeats).sum()
        );
    }
}
