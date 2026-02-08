package com.concert.booking.config;

import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Seat;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final ConcertRepository concertRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (concertRepository.count() > 0) {
            log.info("테스트 데이터가 이미 존재합니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("테스트 데이터 초기화 시작");

        // 콘서트 1
        Concert concert1 = Concert.create("IU 콘서트 - The Golden Hour",
                "아이유 전국 투어 콘서트", "올림픽공원 체조경기장", "IU");
        concertRepository.save(concert1);

        // 콘서트 2
        Concert concert2 = Concert.create("BTS Yet To Come",
                "방탄소년단 스페셜 콘서트", "잠실 종합운동장", "BTS");
        concertRepository.save(concert2);

        // 각 콘서트 2개 스케줄
        createScheduleWithSeats(concert1, LocalDate.now().plusDays(7), LocalTime.of(19, 0));
        createScheduleWithSeats(concert1, LocalDate.now().plusDays(8), LocalTime.of(18, 0));
        createScheduleWithSeats(concert2, LocalDate.now().plusDays(14), LocalTime.of(19, 30));
        createScheduleWithSeats(concert2, LocalDate.now().plusDays(15), LocalTime.of(17, 0));

        log.info("테스트 데이터 초기화 완료: 콘서트 2개, 스케줄 4개, 좌석 200개");
    }

    private void createScheduleWithSeats(Concert concert, LocalDate date, LocalTime time) {
        int totalSeats = 50;
        ConcertSchedule schedule = ConcertSchedule.create(concert, date, time, totalSeats);
        concertScheduleRepository.save(schedule);

        List<Seat> seats = new ArrayList<>();

        // VIP 10석 (150,000원)
        for (int i = 1; i <= 10; i++) {
            seats.add(Seat.create(schedule, "VIP", 1, i, 150000));
        }

        // A구역 20석 (120,000원)
        for (int i = 1; i <= 20; i++) {
            int row = (i - 1) / 10 + 1;
            int seatNum = (i - 1) % 10 + 1;
            seats.add(Seat.create(schedule, "A", row, seatNum, 120000));
        }

        // B구역 20석 (90,000원)
        for (int i = 1; i <= 20; i++) {
            int row = (i - 1) / 10 + 1;
            int seatNum = (i - 1) % 10 + 1;
            seats.add(Seat.create(schedule, "B", row, seatNum, 90000));
        }

        seatRepository.saveAll(seats);
    }
}
