package com.concert.booking.integration;

import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.consumer.SeatReleaseConsumer;
import com.concert.booking.domain.*;
import com.concert.booking.dto.payment.PaymentRequest;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.event.ReservationCancelledEvent;
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
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class SeatReleaseIdempotencyIntegrationTest {

    @Autowired private ReservationService reservationService;
    @Autowired private PaymentService paymentService;
    @Autowired private QueueService queueService;
    @Autowired private SeatReleaseConsumer seatReleaseConsumer;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationSeatRepository reservationSeatRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @Test
    @DisplayName("동일 reservation.cancelled 이벤트 2회 처리 시 좌석과 재고는 1회만 복구된다")
    void duplicate_cancelled_event_restores_inventory_once() {
        Scenario scenario = createScenario();
        Long reservationId = createPendingReservation(scenario);
        ReservationCancelledEvent event = event(reservationId, scenario, "USER_CANCELLED");

        seatReleaseConsumer.handleCancelledReservation(event, ack());
        seatReleaseConsumer.handleCancelledReservation(event, ack());

        assertThat(seatRepository.findById(scenario.seatId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow().getAvailableSeats()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
    }

    @Test
    @DisplayName("RESERVED 좌석에 cancelled 이벤트가 와도 좌석과 재고를 반환하지 않는다")
    void reserved_seat_is_not_released_by_cancelled_event() {
        Scenario scenario = createScenario();
        Long reservationId = createPendingReservation(scenario);
        paymentService.pay(scenario.userId(), new PaymentRequest(reservationId), key("payment"));

        seatReleaseConsumer.handleCancelledReservation(event(reservationId, scenario, "EXPIRED"), ack());

        assertThat(seatRepository.findById(scenario.seatId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.RESERVED);
        assertThat(concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow().getAvailableSeats()).isZero();
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("0");
    }

    @Test
    @DisplayName("HELD 좌석에 expired 이벤트가 오면 좌석과 재고를 반환한다")
    void held_seat_is_released_by_expired_event() {
        Scenario scenario = createScenario();
        Long reservationId = createPendingReservation(scenario);

        seatReleaseConsumer.handleCancelledReservation(event(reservationId, scenario, "EXPIRED"), ack());

        assertThat(seatRepository.findById(scenario.seatId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow().getAvailableSeats()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
    }

    @Test
    @DisplayName("seatHoldKey가 이미 없어도 좌석 반환 처리는 실패하지 않는다")
    void missing_seat_hold_key_does_not_fail_release() {
        Scenario scenario = createScenario();
        Long reservationId = createPendingReservation(scenario);
        redisTemplate.delete(RedisKeyUtil.seatHoldKey(scenario.seatId()));

        seatReleaseConsumer.handleCancelledReservation(event(reservationId, scenario, "EXPIRED"), ack());

        assertThat(seatRepository.findById(scenario.seatId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow().getAvailableSeats()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
    }

    private Long createPendingReservation(Scenario scenario) {
        String queueToken = issueToken(scenario.userId(), scenario.scheduleId());
        Long reservationId = reservationService.reserve(
                scenario.userId(),
                new ReservationRequest(scenario.scheduleId(), List.of(scenario.seatId()), queueToken),
                key("reservation")).id();
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(scenario.scheduleId()), "0");
        redisTemplate.opsForValue().set(RedisKeyUtil.seatHoldKey(scenario.seatId()), String.valueOf(reservationId));
        return reservationId;
    }

    private Scenario createScenario() {
        Concert concert = Concert.create("좌석 반환 테스트 콘서트", "설명", "장소", "아티스트");
        concertRepository.save(concert);
        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(11), LocalTime.of(20, 0), 1);
        concertScheduleRepository.save(schedule);
        Seat seat = Seat.create(schedule, "A", 1, 1, 100000);
        seatRepository.save(seat);
        User user = User.create("seat-release-" + System.nanoTime() + "@test.com", passwordEncoder.encode("password123"), "반환테스터");
        userRepository.save(user);
        return new Scenario(schedule.getId(), seat.getId(), user.getId());
    }

    private String issueToken(Long userId, Long scheduleId) {
        queueService.enter(userId, scheduleId);
        return queueService.issueToken(userId, scheduleId).token();
    }

    private ReservationCancelledEvent event(Long reservationId, Scenario scenario, String reason) {
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        return new ReservationCancelledEvent(
                reservationId,
                scenario.userId(),
                scenario.scheduleId(),
                reservationSeatRepository.findByReservationId(reservationId).stream()
                        .map(rs -> rs.getSeat().getId())
                        .toList(),
                reservation.getTotalAmount(),
                reason
        );
    }

    private Acknowledgment ack() {
        AtomicInteger acknowledged = new AtomicInteger();
        return acknowledged::incrementAndGet;
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private record Scenario(Long scheduleId, Long seatId, Long userId) {
    }
}
