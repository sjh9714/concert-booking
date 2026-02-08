package com.concert.booking.service.concert;

import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Seat;
import com.concert.booking.dto.concert.ConcertResponse;
import com.concert.booking.dto.concert.ConcertScheduleResponse;
import com.concert.booking.dto.concert.SeatResponse;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConcertService {

    private final ConcertRepository concertRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;

    public List<ConcertResponse> getConcerts() {
        return concertRepository.findAll().stream()
                .map(ConcertResponse::from)
                .toList();
    }

    public ConcertResponse getConcert(Long concertId) {
        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new IllegalArgumentException("콘서트를 찾을 수 없습니다: " + concertId));
        return ConcertResponse.from(concert);
    }

    public List<ConcertScheduleResponse> getSchedules(Long concertId) {
        if (!concertRepository.existsById(concertId)) {
            throw new IllegalArgumentException("콘서트를 찾을 수 없습니다: " + concertId);
        }
        List<ConcertSchedule> schedules = concertScheduleRepository.findByConcertId(concertId);
        return schedules.stream()
                .map(ConcertScheduleResponse::from)
                .toList();
    }

    public List<SeatResponse> getSeats(Long concertId, Long scheduleId) {
        ConcertSchedule schedule = concertScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다: " + scheduleId));

        if (!schedule.getConcert().getId().equals(concertId)) {
            throw new IllegalArgumentException("해당 콘서트의 스케줄이 아닙니다.");
        }

        List<Seat> seats = seatRepository.findByScheduleId(scheduleId);
        return seats.stream()
                .map(SeatResponse::from)
                .toList();
    }
}
