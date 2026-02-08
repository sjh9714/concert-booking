package com.concert.booking.controller;

import com.concert.booking.dto.concert.ConcertResponse;
import com.concert.booking.dto.concert.ConcertScheduleResponse;
import com.concert.booking.dto.concert.SeatResponse;
import com.concert.booking.service.concert.ConcertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/concerts")
@RequiredArgsConstructor
public class ConcertController {

    private final ConcertService concertService;

    @GetMapping
    public ResponseEntity<List<ConcertResponse>> getConcerts() {
        return ResponseEntity.ok(concertService.getConcerts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConcertResponse> getConcert(@PathVariable Long id) {
        return ResponseEntity.ok(concertService.getConcert(id));
    }

    @GetMapping("/{id}/schedules")
    public ResponseEntity<List<ConcertScheduleResponse>> getSchedules(@PathVariable Long id) {
        return ResponseEntity.ok(concertService.getSchedules(id));
    }

    @GetMapping("/{id}/schedules/{scheduleId}/seats")
    public ResponseEntity<List<SeatResponse>> getSeats(@PathVariable Long id,
                                                        @PathVariable Long scheduleId) {
        return ResponseEntity.ok(concertService.getSeats(id, scheduleId));
    }
}
