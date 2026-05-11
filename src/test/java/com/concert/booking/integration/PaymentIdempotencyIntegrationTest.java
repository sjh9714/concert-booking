package com.concert.booking.integration;

import com.concert.booking.common.exception.ConflictException;
import com.concert.booking.common.exception.ForbiddenException;
import com.concert.booking.common.exception.PaymentException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.*;
import com.concert.booking.dto.payment.PaymentRequest;
import com.concert.booking.dto.payment.PaymentResponse;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.repository.*;
import com.concert.booking.service.payment.PaymentService;
import com.concert.booking.service.queue.QueueService;
import com.concert.booking.service.reservation.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
class PaymentIdempotencyIntegrationTest {

    @Autowired private ReservationService reservationService;
    @Autowired private PaymentService paymentService;
    @Autowired private QueueService queueService;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @Test
    @DisplayName("같은 payment idempotency key 재시도 시 결제는 1개만 생성되고 기존 응답을 반환한다")
    void same_payment_key_replays_existing_payment() {
        Scenario scenario = createScenario(2);
        Long reservationId = createPendingReservation(scenario, scenario.seatIds().get(0));
        String paymentKey = key("payment-same");

        PaymentResponse first = paymentService.pay(scenario.userId(), new PaymentRequest(reservationId), paymentKey);
        PaymentResponse replay = paymentService.pay(scenario.userId(), new PaymentRequest(reservationId), paymentKey);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(paymentRepository.findByReservationId(reservationId)).isPresent();
    }

    @Test
    @DisplayName("이미 결제된 reservation에 다른 payment key로 결제하면 409로 거부한다")
    void different_payment_key_for_confirmed_reservation_fails() {
        Scenario scenario = createScenario(2);
        Long reservationId = createPendingReservation(scenario, scenario.seatIds().get(0));

        paymentService.pay(scenario.userId(), new PaymentRequest(reservationId), key("payment-first"));

        assertThatThrownBy(() -> paymentService.pay(
                scenario.userId(),
                new PaymentRequest(reservationId),
                key("payment-second")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("같은 reservation/payment key 동시 결제 요청 20개에서 결제는 1개만 생성된다")
    void concurrent_same_payment_key_creates_one_payment() throws InterruptedException {
        Scenario scenario = createScenario(2);
        Long reservationId = createPendingReservation(scenario, scenario.seatIds().get(0));
        String paymentKey = key("payment-concurrent");

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(20);
        var executor = Executors.newFixedThreadPool(20);
        Set<Long> paymentIds = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    PaymentResponse response = paymentService.pay(scenario.userId(), new PaymentRequest(reservationId), paymentKey);
                    paymentIds.add(response.id());
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
        assertThat(doneGate.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(errors).isEmpty();
        assertThat(paymentIds).hasSize(1);
        assertThat(paymentRepository.findByReservationId(reservationId)).isPresent();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("만료된 reservation은 결제를 거부한다")
    void expired_reservation_payment_fails() {
        Scenario scenario = createScenario(1);
        Reservation reservation = Reservation.create(
                userRepository.findById(scenario.userId()).orElseThrow(),
                concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow(),
                100000,
                LocalDateTime.now().minusMinutes(1));
        reservationRepository.save(reservation);

        assertThatThrownBy(() -> paymentService.pay(
                scenario.userId(),
                new PaymentRequest(reservation.getId()),
                key("payment-expired")))
                .isInstanceOf(PaymentException.class);
    }

    @Test
    @DisplayName("다른 사용자의 reservation 결제 시도는 거부한다")
    void other_user_reservation_payment_fails() {
        Scenario scenario = createScenario(2);
        Long reservationId = createPendingReservation(scenario, scenario.seatIds().get(0));
        User otherUser = createUser("payment-other-" + System.nanoTime() + "@test.com");

        assertThatThrownBy(() -> paymentService.pay(
                otherUser.getId(),
                new PaymentRequest(reservationId),
                key("payment-forbidden")))
                .isInstanceOf(ForbiddenException.class);
    }

    private Long createPendingReservation(Scenario scenario, Long seatId) {
        String queueToken = issueToken(scenario.userId(), scenario.scheduleId());
        return reservationService.reserve(
                scenario.userId(),
                new ReservationRequest(scenario.scheduleId(), List.of(seatId), queueToken),
                key("reservation")).id();
    }

    private Scenario createScenario(int seatCount) {
        Concert concert = Concert.create("결제 멱등성 테스트 콘서트", "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(31), LocalTime.of(20, 0), seatCount);
        concertScheduleRepository.save(schedule);

        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= seatCount; i++) {
            seats.add(Seat.create(schedule, "A", 1, i, 100000));
        }
        seatRepository.saveAll(seats);
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(schedule.getId()), String.valueOf(seatCount));

        User user = createUser("payment-idempotency-" + System.nanoTime() + "@test.com");

        return new Scenario(
                schedule.getId(),
                seats.stream().map(Seat::getId).toList(),
                user.getId());
    }

    private User createUser(String email) {
        User user = User.create(email, passwordEncoder.encode("password123"), "결제테스터");
        return userRepository.save(user);
    }

    private String issueToken(Long userId, Long scheduleId) {
        queueService.enter(userId, scheduleId);
        return queueService.issueToken(userId, scheduleId).token();
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private record Scenario(Long scheduleId, List<Long> seatIds, Long userId) {
    }
}
