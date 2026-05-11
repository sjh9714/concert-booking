package com.concert.booking.integration;

import com.concert.booking.common.exception.BadRequestException;
import com.concert.booking.common.exception.ConflictException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.*;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.repository.*;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class ReservationIdempotencyIntegrationTest {

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
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @Test
    @DisplayName("세 예약 전략 모두 같은 idempotency key 동일 요청 재시도 시 기존 예매를 반환한다")
    void all_strategies_replay_same_reservation_for_same_idempotency_key() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name(), 4);
            String idempotencyKey = key(strategy.name(), "same");
            String queueToken = issueToken(scenario.userId(), scenario.scheduleId());
            ReservationRequest request = request(scenario.scheduleId(), List.of(scenario.seatIds().get(0)), queueToken);

            ReservationResponse first = strategy.service().reserve(scenario.userId(), request, idempotencyKey);
            ReservationResponse replay = strategy.service().reserve(scenario.userId(), request, idempotencyKey);

            assertThat(replay.id()).as(strategy.name()).isEqualTo(first.id());
            assertThat(reservationsFor(scenario.userId(), scenario.scheduleId())).as(strategy.name()).hasSize(1);
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 같은 idempotency key로 다른 좌석을 요청하면 409로 거부한다")
    void all_strategies_reject_same_key_with_different_seats() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name(), 4);
            String idempotencyKey = key(strategy.name(), "conflict");
            String queueToken = issueToken(scenario.userId(), scenario.scheduleId());

            strategy.service().reserve(
                    scenario.userId(),
                    request(scenario.scheduleId(), List.of(scenario.seatIds().get(0)), queueToken),
                    idempotencyKey);

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.userId(),
                    request(scenario.scheduleId(), List.of(scenario.seatIds().get(1)), queueToken),
                    idempotencyKey))
                    .as(strategy.name())
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 같은 idempotency key 동시 요청 20개에서 예매를 1개만 생성한다")
    void all_strategies_create_one_reservation_for_concurrent_same_key_requests() throws InterruptedException {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name(), 4);
            String idempotencyKey = key(strategy.name(), "concurrent");
            String queueToken = issueToken(scenario.userId(), scenario.scheduleId());
            ReservationRequest request = request(scenario.scheduleId(), List.of(scenario.seatIds().get(0)), queueToken);

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(20);
            var executor = Executors.newFixedThreadPool(20);
            Set<Long> reservationIds = ConcurrentHashMap.newKeySet();
            List<Throwable> errors = new ArrayList<>();

            for (int i = 0; i < 20; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        ReservationResponse response = strategy.service().reserve(scenario.userId(), request, idempotencyKey);
                        reservationIds.add(response.id());
                    } catch (Throwable e) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(doneGate.await(10, TimeUnit.SECONDS)).as(strategy.name()).isTrue();
            executor.shutdown();

            assertThat(errors).as(strategy.name()).isEmpty();
            assertThat(reservationIds).as(strategy.name()).hasSize(1);
            assertThat(reservationsFor(scenario.userId(), scenario.scheduleId())).as(strategy.name()).hasSize(1);
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 좌석 중복, 빈 배열, 4석 초과 요청을 거부한다")
    void all_strategies_reject_invalid_seat_ids() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name(), 6);
            String queueToken = issueToken(scenario.userId(), scenario.scheduleId());

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.userId(),
                    request(scenario.scheduleId(), List.of(scenario.seatIds().get(0), scenario.seatIds().get(0)), queueToken),
                    key(strategy.name(), "duplicate")))
                    .as(strategy.name() + " duplicate")
                    .isInstanceOf(BadRequestException.class);

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.userId(),
                    request(scenario.scheduleId(), List.of(), queueToken),
                    key(strategy.name(), "empty")))
                    .as(strategy.name() + " empty")
                    .isInstanceOf(BadRequestException.class);

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.userId(),
                    request(scenario.scheduleId(), scenario.seatIds().subList(0, 5), queueToken),
                    key(strategy.name(), "too-many")))
                    .as(strategy.name() + " too many")
                    .isInstanceOf(BadRequestException.class);
        }
    }

    private List<StrategyCase> strategies() {
        return List.of(
                new StrategyCase("pessimistic", pessimisticService),
                new StrategyCase("optimistic", optimisticService),
                new StrategyCase("distributed", distributedService)
        );
    }

    private Scenario createScenario(String suffix, int seatCount) {
        Concert concert = Concert.create("멱등성 테스트 콘서트 " + suffix, "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(30), LocalTime.of(20, 0), seatCount);
        concertScheduleRepository.save(schedule);

        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= seatCount; i++) {
            seats.add(Seat.create(schedule, "A", 1, i, 100000));
        }
        seatRepository.saveAll(seats);
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(schedule.getId()), String.valueOf(seatCount));

        User user = User.create(
                "reservation-idempotency-" + suffix + "-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "멱등테스터");
        userRepository.save(user);

        return new Scenario(
                schedule.getId(),
                seats.stream().map(Seat::getId).toList(),
                user.getId());
    }

    private String issueToken(Long userId, Long scheduleId) {
        queueService.enter(userId, scheduleId);
        return queueService.issueToken(userId, scheduleId).token();
    }

    private ReservationRequest request(Long scheduleId, List<Long> seatIds, String queueToken) {
        return new ReservationRequest(scheduleId, seatIds, queueToken);
    }

    private String key(String strategy, String suffix) {
        return strategy + "-" + suffix + "-" + UUID.randomUUID();
    }

    private List<Reservation> reservationsFor(Long userId, Long scheduleId) {
        return reservationRepository.findByUserId(userId).stream()
                .filter(reservation -> reservation.getSchedule().getId().equals(scheduleId))
                .toList();
    }

    private record StrategyCase(String name, ReservationService service) {
    }

    private record Scenario(Long scheduleId, List<Long> seatIds, Long userId) {
    }
}
