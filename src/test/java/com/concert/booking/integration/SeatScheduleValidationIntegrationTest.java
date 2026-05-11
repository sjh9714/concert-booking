package com.concert.booking.integration;

import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Seat;
import com.concert.booking.domain.SeatStatus;
import com.concert.booking.domain.User;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import com.concert.booking.service.queue.QueueService;
import com.concert.booking.service.reservation.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class SeatScheduleValidationIntegrationTest {

    @Autowired
    @Qualifier("pessimisticLockReservationService")
    private ReservationService pessimisticService;

    @Autowired
    @Qualifier("optimisticLockReservationService")
    private ReservationService optimisticService;

    @Autowired
    @Qualifier("distributedLockReservationService")
    private ReservationService distributedService;

    @Autowired private QueueService queueService;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @Test
    @DisplayName("세 예약 전략 모두 다른 schedule에 속한 seatId만 들어오면 실패하고 상태를 바꾸지 않는다")
    void all_strategies_reject_seat_from_other_schedule() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name() + "-other-only");
            String token = issueToken(scenario.userId(), scenario.scheduleId());

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.userId(),
                    new ReservationRequest(scenario.scheduleId(), List.of(scenario.otherSeat1Id()), token),
                    key(strategy.name(), "other-only")))
                    .as(strategy.name())
                    .isInstanceOf(SeatNotAvailableException.class);

            assertUnchanged(strategy.name(), scenario, token);
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 일부 seatId만 다른 schedule에 속해도 All-or-Nothing으로 실패한다")
    void all_strategies_reject_mixed_schedule_seats() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name() + "-mixed");
            String token = issueToken(scenario.userId(), scenario.scheduleId());

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.userId(),
                    new ReservationRequest(
                            scenario.scheduleId(),
                            List.of(scenario.seat1Id(), scenario.otherSeat1Id()),
                            token),
                    key(strategy.name(), "mixed")))
                    .as(strategy.name())
                    .isInstanceOf(SeatNotAvailableException.class);

            assertUnchanged(strategy.name(), scenario, token);
        }
    }

    private void assertUnchanged(String strategy, Scenario scenario, String token) {
        assertThat(queueService.validateToken(scenario.userId(), scenario.scheduleId(), token))
                .as(strategy + " token should remain reusable after failed reservation")
                .isTrue();
        assertThat(concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow().getAvailableSeats())
                .as(strategy + " requested schedule available seats")
                .isEqualTo(2);
        assertThat(concertScheduleRepository.findById(scenario.otherScheduleId()).orElseThrow().getAvailableSeats())
                .as(strategy + " other schedule available seats")
                .isEqualTo(2);
        assertThat(seatRepository.findById(scenario.seat1Id()).orElseThrow().getStatus())
                .as(strategy + " requested schedule seat1")
                .isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seatRepository.findById(scenario.seat2Id()).orElseThrow().getStatus())
                .as(strategy + " requested schedule seat2")
                .isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seatRepository.findById(scenario.otherSeat1Id()).orElseThrow().getStatus())
                .as(strategy + " other schedule seat")
                .isEqualTo(SeatStatus.AVAILABLE);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId())))
                .as(strategy + " requested schedule Redis stock")
                .isEqualTo("2");
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.otherScheduleId())))
                .as(strategy + " other schedule Redis stock")
                .isEqualTo("2");
    }

    private List<StrategyCase> strategies() {
        return List.of(
                new StrategyCase("pessimistic", pessimisticService),
                new StrategyCase("optimistic", optimisticService),
                new StrategyCase("distributed", distributedService)
        );
    }

    private Scenario createScenario(String suffix) {
        Concert concert = Concert.create("좌석 스케줄 검증 콘서트 " + suffix, "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(31), LocalTime.of(20, 0), 2);
        ConcertSchedule otherSchedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(32), LocalTime.of(20, 0), 2);
        concertScheduleRepository.saveAll(List.of(schedule, otherSchedule));

        Seat seat1 = Seat.create(schedule, "A", 1, 1, 100000);
        Seat seat2 = Seat.create(schedule, "A", 1, 2, 100000);
        Seat otherSeat1 = Seat.create(otherSchedule, "B", 1, 1, 100000);
        Seat otherSeat2 = Seat.create(otherSchedule, "B", 1, 2, 100000);
        seatRepository.saveAll(List.of(seat1, seat2, otherSeat1, otherSeat2));

        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(schedule.getId()), "2");
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(otherSchedule.getId()), "2");

        User user = userRepository.save(User.create(
                "seat-schedule-" + suffix + "-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "스케줄검증"));

        return new Scenario(
                schedule.getId(),
                otherSchedule.getId(),
                seat1.getId(),
                seat2.getId(),
                otherSeat1.getId(),
                user.getId());
    }

    private String issueToken(Long userId, Long scheduleId) {
        queueService.enter(userId, scheduleId);
        return queueService.issueToken(userId, scheduleId).token();
    }

    private String key(String strategy, String suffix) {
        return strategy + "-seat-schedule-" + suffix + "-" + UUID.randomUUID();
    }

    private record StrategyCase(String name, ReservationService service) {
    }

    private record Scenario(
            Long scheduleId,
            Long otherScheduleId,
            Long seat1Id,
            Long seat2Id,
            Long otherSeat1Id,
            Long userId
    ) {
    }
}
