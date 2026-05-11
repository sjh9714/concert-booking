package com.concert.booking.integration;

import com.concert.booking.common.exception.InvalidReservationStateException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.*;
import com.concert.booking.dto.payment.PaymentRequest;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.repository.*;
import com.concert.booking.service.payment.PaymentService;
import com.concert.booking.service.queue.QueueService;
import com.concert.booking.service.reservation.ReservationExpirationScheduler;
import com.concert.booking.service.reservation.ReservationService;
import com.concert.booking.service.reservation.SeatReleaseService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "kafka.consumer.seat-release-group=seat-release-state-race")
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class ReservationStateTransitionRaceIntegrationTest {

    @Autowired private ReservationService reservationService;
    @Autowired private PaymentService paymentService;
    @Autowired private QueueService queueService;
    @Autowired private ReservationExpirationScheduler expirationScheduler;
    @Autowired private SeatReleaseService seatReleaseService;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ReservationSeatRepository reservationSeatRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void clearOutbox() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("payment와 expiration이 동시에 실행되어도 하나의 상태 전이만 성공한다")
    void payment_and_expiration_race_has_single_winner() throws InterruptedException {
        Scenario scenario = createScenario(1);
        Long reservationId = createPendingReservation(scenario, scenario.seatIds().get(0), LocalDateTime.now().minusSeconds(1));

        RaceResult result = race(
                () -> paymentService.pay(scenario.userId(), new PaymentRequest(reservationId), key("payment-race")),
                () -> expireOrFail(reservationId));

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(reservation.getStatus()).isIn(ReservationStatus.CONFIRMED, ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("payment 성공 후 expiration은 좌석을 반환하지 않는다")
    void expiration_skips_confirmed_reservation_after_payment() {
        Scenario scenario = createScenario(1);
        Long reservationId = createPendingReservation(scenario, scenario.seatIds().get(0), LocalDateTime.now().plusMinutes(5));

        paymentService.pay(scenario.userId(), new PaymentRequest(reservationId), key("payment-before-expire"));
        assertThat(expirationScheduler.expireReservation(reservationId, LocalDateTime.now().plusMinutes(10))).isFalse();

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        Seat seat = seatRepository.findById(scenario.seatIds().get(0)).orElseThrow();
        ConcertSchedule schedule = concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.RESERVED);
        assertThat(schedule.getAvailableSeats()).isZero();
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("0");
    }

    @Test
    @DisplayName("expiration 성공 후 payment는 실패하고 좌석과 재고는 한 번만 복구된다")
    void payment_fails_after_expiration_and_inventory_is_restored_once() {
        Scenario scenario = createScenario(1);
        Long reservationId = createPendingReservation(scenario, scenario.seatIds().get(0), LocalDateTime.now().minusSeconds(1));

        assertThat(expirationScheduler.expireReservation(reservationId, LocalDateTime.now())).isTrue();
        seatReleaseService.releaseHeldSeats(reservationId, "EXPIRED");
        awaitSeatStatus(scenario.seatIds().get(0), SeatStatus.AVAILABLE);

        assertThatThrownBy(() -> paymentService.pay(
                scenario.userId(),
                new PaymentRequest(reservationId),
                key("payment-after-expire")))
                .isInstanceOf(RuntimeException.class);

        ConcertSchedule schedule = concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(schedule.getAvailableSeats()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
    }

    @Test
    @DisplayName("cancel과 payment가 동시에 실행되어도 하나의 상태 전이만 성공한다")
    void cancel_and_payment_race_has_single_winner() throws InterruptedException {
        Scenario scenario = createScenario(1);
        Long reservationId = createPendingReservation(scenario, scenario.seatIds().get(0), LocalDateTime.now().plusMinutes(5));

        RaceResult result = race(
                () -> reservationService.cancelReservation(scenario.userId(), reservationId),
                () -> paymentService.pay(scenario.userId(), new PaymentRequest(reservationId), key("payment-cancel-race")));

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(reservation.getStatus()).isIn(ReservationStatus.CANCELLED, ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("cancel과 expiration이 동시에 실행되어도 좌석과 재고는 한 번만 복구된다")
    void cancel_and_expiration_race_restores_inventory_once() throws InterruptedException {
        Scenario scenario = createScenario(1);
        Long reservationId = createPendingReservation(scenario, scenario.seatIds().get(0), LocalDateTime.now().minusSeconds(1));

        RaceResult result = race(
                () -> reservationService.cancelReservation(scenario.userId(), reservationId),
                () -> expireOrFail(reservationId));

        seatReleaseService.releaseHeldSeats(reservationId, "STATE_RACE");
        awaitSeatStatus(scenario.seatIds().get(0), SeatStatus.AVAILABLE);
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        ConcertSchedule schedule = concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow();

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(reservation.getStatus()).isIn(ReservationStatus.CANCELLED, ReservationStatus.EXPIRED);
        assertThat(schedule.getAvailableSeats()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
    }

    @Test
    @DisplayName("이미 EXPIRED된 reservation cancel은 재고를 중복 복원하지 않는다")
    void cancelling_expired_reservation_does_not_restore_inventory_twice() {
        Scenario scenario = createScenario(1);
        Long reservationId = createPendingReservation(scenario, scenario.seatIds().get(0), LocalDateTime.now().minusSeconds(1));

        assertThat(expirationScheduler.expireReservation(reservationId, LocalDateTime.now())).isTrue();
        seatReleaseService.releaseHeldSeats(reservationId, "EXPIRED");
        awaitSeatStatus(scenario.seatIds().get(0), SeatStatus.AVAILABLE);

        assertThatThrownBy(() -> reservationService.cancelReservation(scenario.userId(), reservationId))
                .isInstanceOf(InvalidReservationStateException.class);

        ConcertSchedule schedule = concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow();
        assertThat(schedule.getAvailableSeats()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
    }

    private Long createPendingReservation(Scenario scenario, Long seatId, LocalDateTime expiresAt) {
        String queueToken = issueToken(scenario.userId(), scenario.scheduleId());
        Long reservationId = reservationService.reserve(
                scenario.userId(),
                new ReservationRequest(scenario.scheduleId(), List.of(seatId), queueToken),
                key("reservation")).id();
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        ReflectionTestUtils.setField(reservation, "expiresAt", expiresAt);
        reservationRepository.saveAndFlush(reservation);
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(scenario.scheduleId()), "0");
        return reservationId;
    }

    private Scenario createScenario(int seatCount) {
        Concert concert = Concert.create("상태 전이 테스트 콘서트", "설명", "장소", "아티스트");
        concertRepository.save(concert);
        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(10), LocalTime.of(20, 0), seatCount);
        concertScheduleRepository.save(schedule);
        List<Seat> seats = java.util.stream.IntStream.rangeClosed(1, seatCount)
                .mapToObj(i -> Seat.create(schedule, "A", 1, i, 100000))
                .toList();
        seatRepository.saveAll(seats);
        User user = User.create("state-race-" + System.nanoTime() + "@test.com", passwordEncoder.encode("password123"), "상태테스터");
        userRepository.save(user);
        return new Scenario(schedule.getId(), seats.stream().map(Seat::getId).toList(), user.getId());
    }

    private String issueToken(Long userId, Long scheduleId) {
        queueService.enter(userId, scheduleId);
        return queueService.issueToken(userId, scheduleId).token();
    }

    private RaceResult race(ThrowingRunnable first, ThrowingRunnable second) throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        var executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> runRacer(first, startGate, doneGate, successCount, firstError));
        executor.submit(() -> runRacer(second, startGate, doneGate, successCount, secondError));

        startGate.countDown();
        assertThat(doneGate.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        return new RaceResult(successCount.get(), firstError.get(), secondError.get());
    }

    private void runRacer(ThrowingRunnable runnable, CountDownLatch startGate, CountDownLatch doneGate,
                          AtomicInteger successCount, AtomicReference<Throwable> error) {
        try {
            startGate.await();
            runnable.run();
            successCount.incrementAndGet();
        } catch (Throwable e) {
            error.set(e);
        } finally {
            doneGate.countDown();
        }
    }

    private void awaitSeatStatus(Long seatId, SeatStatus status) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(seatRepository.findById(seatId).orElseThrow().getStatus()).isEqualTo(status));
    }

    private void expireOrFail(Long reservationId) {
        if (!expirationScheduler.expireReservation(reservationId, LocalDateTime.now())) {
            throw new InvalidReservationStateException("만료 상태 전이가 실행되지 않았습니다.");
        }
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record RaceResult(int successCount, Throwable firstError, Throwable secondError) {
    }

    private record Scenario(Long scheduleId, List<Long> seatIds, Long userId) {
    }
}
