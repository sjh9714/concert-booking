package com.concert.booking.integration;

import com.concert.booking.common.exception.InvalidQueueTokenException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.*;
import com.concert.booking.dto.reservation.ReservationRequest;
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

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class QueueTokenPolicyIntegrationTest {

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
    @DisplayName("세 예약 전략 모두 token 없이 예매 요청하면 실패한다")
    void all_strategies_reject_missing_token() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name());

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.user1Id(),
                    request(scenario.scheduleId(), List.of(scenario.seat1Id()), null),
                    key(strategy.name(), "missing")))
                    .as(strategy.name())
                    .isInstanceOf(InvalidQueueTokenException.class);
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 잘못된 token이면 예매 요청을 거부한다")
    void all_strategies_reject_wrong_token() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name());

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.user1Id(),
                    request(scenario.scheduleId(), List.of(scenario.seat1Id()), "wrong-token"),
                    key(strategy.name(), "wrong")))
                    .as(strategy.name())
                    .isInstanceOf(InvalidQueueTokenException.class);
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 다른 scheduleId의 token이면 예매 요청을 거부한다")
    void all_strategies_reject_token_bound_to_other_schedule() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name());
            String otherScheduleToken = issueToken(scenario.user1Id(), scenario.otherScheduleId());

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.user1Id(),
                    request(scenario.scheduleId(), List.of(scenario.seat1Id()), otherScheduleToken),
                    key(strategy.name(), "other-schedule")))
                    .as(strategy.name())
                    .isInstanceOf(InvalidQueueTokenException.class);
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 다른 userId의 token이면 예매 요청을 거부한다")
    void all_strategies_reject_token_bound_to_other_user() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name());
            String otherUserToken = issueToken(scenario.user2Id(), scenario.scheduleId());

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.user1Id(),
                    request(scenario.scheduleId(), List.of(scenario.seat1Id()), otherUserToken),
                    key(strategy.name(), "other-user")))
                    .as(strategy.name())
                    .isInstanceOf(InvalidQueueTokenException.class);
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 만료된 token이면 예매 요청을 거부한다")
    void all_strategies_reject_expired_token() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name());
            String token = issueToken(scenario.user1Id(), scenario.scheduleId());
            redisTemplate.delete(RedisKeyUtil.tokenKey(scenario.user1Id(), scenario.scheduleId()));

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.user1Id(),
                    request(scenario.scheduleId(), List.of(scenario.seat1Id()), token),
                    key(strategy.name(), "expired")))
                    .as(strategy.name())
                    .isInstanceOf(InvalidQueueTokenException.class);
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 예매 성공 시 token을 삭제하고 재사용을 거부한다")
    void all_strategies_consume_token_only_after_success() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name());
            String token = issueToken(scenario.user1Id(), scenario.scheduleId());

            strategy.service().reserve(
                    scenario.user1Id(),
                    request(scenario.scheduleId(), List.of(scenario.seat1Id()), token),
                    key(strategy.name(), "success"));

            assertThat(queueService.validateToken(scenario.user1Id(), scenario.scheduleId(), token))
                    .as(strategy.name())
                    .isFalse();

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.user1Id(),
                    request(scenario.scheduleId(), List.of(scenario.seat2Id()), token),
                    key(strategy.name(), "reuse")))
                    .as(strategy.name())
                    .isInstanceOf(InvalidQueueTokenException.class);
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 예매 실패 시 token을 삭제하지 않는다")
    void all_strategies_keep_token_when_reservation_fails() {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name());
            String winnerToken = issueToken(scenario.user2Id(), scenario.scheduleId());
            strategy.service().reserve(
                    scenario.user2Id(),
                    request(scenario.scheduleId(), List.of(scenario.seat1Id()), winnerToken),
                    key(strategy.name(), "winner"));

            String retryToken = issueToken(scenario.user1Id(), scenario.scheduleId());

            assertThatThrownBy(() -> strategy.service().reserve(
                    scenario.user1Id(),
                    request(scenario.scheduleId(), List.of(scenario.seat1Id()), retryToken),
                    key(strategy.name(), "failed")))
                    .as(strategy.name())
                    .isNotInstanceOf(InvalidQueueTokenException.class);

            assertThat(queueService.validateToken(scenario.user1Id(), scenario.scheduleId(), retryToken))
                    .as(strategy.name())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("세 예약 전략 모두 같은 token의 동시 재사용을 하나만 허용한다")
    void all_strategies_allow_only_one_concurrent_use_of_same_token() throws InterruptedException {
        for (StrategyCase strategy : strategies()) {
            Scenario scenario = createScenario(strategy.name());
            String token = issueToken(scenario.user1Id(), scenario.scheduleId());

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(2);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger invalidTokenCount = new AtomicInteger();
            var executor = Executors.newFixedThreadPool(2);

            for (Long seatId : List.of(scenario.seat1Id(), scenario.seat2Id())) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        strategy.service().reserve(
                                scenario.user1Id(),
                                request(scenario.scheduleId(), List.of(seatId), token),
                                key(strategy.name(), "concurrent-" + seatId));
                        successCount.incrementAndGet();
                    } catch (InvalidQueueTokenException e) {
                        invalidTokenCount.incrementAndGet();
                    } catch (Exception ignored) {
                        // Any non-token exception means the token gate did not protect this call.
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            doneGate.await();
            executor.shutdown();

            assertThat(successCount.get()).as(strategy.name()).isEqualTo(1);
            assertThat(invalidTokenCount.get()).as(strategy.name()).isEqualTo(1);
            assertThat(queueService.validateToken(scenario.user1Id(), scenario.scheduleId(), token))
                    .as(strategy.name())
                    .isFalse();
        }
    }

    private List<StrategyCase> strategies() {
        return List.of(
                new StrategyCase("pessimistic", pessimisticService),
                new StrategyCase("optimistic", optimisticService),
                new StrategyCase("distributed", distributedService)
        );
    }

    private Scenario createScenario(String suffix) {
        Concert concert = Concert.create("토큰 정책 테스트 콘서트 " + suffix, "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(21), LocalTime.of(20, 0), 2);
        ConcertSchedule otherSchedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(22), LocalTime.of(20, 0), 2);
        concertScheduleRepository.saveAll(List.of(schedule, otherSchedule));

        Seat seat1 = Seat.create(schedule, "A", 1, 1, 100000);
        Seat seat2 = Seat.create(schedule, "A", 1, 2, 100000);
        Seat otherSeat = Seat.create(otherSchedule, "A", 1, 1, 100000);
        seatRepository.saveAll(List.of(seat1, seat2, otherSeat));

        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(schedule.getId()), "2");
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(otherSchedule.getId()), "2");

        User user1 = createUser("token-" + suffix + "-u1-" + System.nanoTime() + "@test.com");
        User user2 = createUser("token-" + suffix + "-u2-" + System.nanoTime() + "@test.com");

        return new Scenario(schedule.getId(), otherSchedule.getId(), seat1.getId(), seat2.getId(), user1.getId(), user2.getId());
    }

    private User createUser(String email) {
        User user = User.create(email, passwordEncoder.encode("password123"), "토큰테스터");
        return userRepository.save(user);
    }

    private String issueToken(Long userId, Long scheduleId) {
        queueService.enter(userId, scheduleId);
        return queueService.issueToken(userId, scheduleId).token();
    }

    private ReservationRequest request(Long scheduleId, List<Long> seatIds, String queueToken) {
        try {
            Constructor<ReservationRequest> constructor =
                    ReservationRequest.class.getConstructor(Long.class, List.class, String.class);
            return constructor.newInstance(scheduleId, seatIds, queueToken);
        } catch (NoSuchMethodException e) {
            try {
                Constructor<ReservationRequest> constructor =
                        ReservationRequest.class.getConstructor(Long.class, List.class);
                return constructor.newInstance(scheduleId, seatIds);
            } catch (Exception nested) {
                throw new IllegalStateException(nested);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String key(String strategy, String suffix) {
        return strategy + "-" + suffix + "-" + UUID.randomUUID();
    }

    private record StrategyCase(String name, ReservationService service) {
    }

    private record Scenario(
            Long scheduleId,
            Long otherScheduleId,
            Long seat1Id,
            Long seat2Id,
            Long user1Id,
            Long user2Id
    ) {
    }
}
