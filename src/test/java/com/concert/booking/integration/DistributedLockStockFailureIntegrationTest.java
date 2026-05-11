package com.concert.booking.integration;

import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.common.exception.SoldOutException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Seat;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class DistributedLockStockFailureIntegrationTest {

    @Autowired
    @Qualifier("distributedLockReservationService")
    private ReservationService reservationService;

    @Autowired private QueueService queueService;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @Test
    @DisplayName("Redis stock key가 없으면 DB AVAILABLE 기준으로 lazy init 후 예매한다")
    void missing_stock_key_is_lazy_initialized_before_distributed_reservation() {
        Scenario scenario = createScenario(2);
        redisTemplate.delete(RedisKeyUtil.stockKey(scenario.scheduleId()));
        String token = issueToken(scenario.userId(), scenario.scheduleId());

        reservationService.reserve(
                scenario.userId(),
                new ReservationRequest(scenario.scheduleId(), List.of(scenario.seatIds().get(0)), token),
                "stock-lazy-init-" + System.nanoTime()
        );

        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
    }

    @Test
    @DisplayName("SoldOutException 발생 시 Redis stock은 음수로 남지 않고 token은 유지된다")
    void sold_out_restores_negative_stock_and_keeps_token() {
        Scenario scenario = createScenario(1);
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(scenario.scheduleId()), "0");
        String token = issueToken(scenario.userId(), scenario.scheduleId());

        assertThatThrownBy(() -> reservationService.reserve(
                scenario.userId(),
                new ReservationRequest(scenario.scheduleId(), List.of(scenario.seatIds().get(0)), token),
                "stock-sold-out-" + System.nanoTime()
        )).isInstanceOf(SoldOutException.class);

        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("0");
        assertThat(queueService.validateToken(scenario.userId(), scenario.scheduleId(), token)).isTrue();
    }

    @Test
    @DisplayName("Redis decrement 후 좌석 불가가 발견되면 Redis stock을 복원하고 token은 유지된다")
    void seat_not_available_after_decrement_restores_stock_and_keeps_token() {
        Scenario scenario = createScenario(1);
        Seat seat = seatRepository.findById(scenario.seatIds().get(0)).orElseThrow();
        seat.hold();
        seatRepository.saveAndFlush(seat);
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(scenario.scheduleId()), "1");
        String token = issueToken(scenario.userId(), scenario.scheduleId());

        assertThatThrownBy(() -> reservationService.reserve(
                scenario.userId(),
                new ReservationRequest(scenario.scheduleId(), List.of(seat.getId()), token),
                "stock-seat-unavailable-" + System.nanoTime()
        )).isInstanceOf(SeatNotAvailableException.class);

        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
        assertThat(queueService.validateToken(scenario.userId(), scenario.scheduleId(), token)).isTrue();
    }

    private String issueToken(Long userId, Long scheduleId) {
        queueService.enter(userId, scheduleId);
        return queueService.issueToken(userId, scheduleId).token();
    }

    private Scenario createScenario(int seatCount) {
        Concert concert = Concert.create("분산락 stock 실패 테스트 " + System.nanoTime(), "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(20), LocalTime.of(20, 0), seatCount);
        concertScheduleRepository.save(schedule);

        List<Seat> seats = java.util.stream.IntStream.rangeClosed(1, seatCount)
                .mapToObj(i -> Seat.create(schedule, "A", 1, i, 100000))
                .toList();
        seatRepository.saveAll(seats);
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(schedule.getId()), String.valueOf(seatCount));

        User user = User.create(
                "distributed-stock-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "분산락stock"
        );
        userRepository.save(user);

        return new Scenario(schedule.getId(), seats.stream().map(Seat::getId).toList(), user.getId());
    }

    private record Scenario(Long scheduleId, List<Long> seatIds, Long userId) {
    }
}
