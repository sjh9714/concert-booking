package com.concert.booking.integration;

import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.common.jwt.JwtProvider;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Payment;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationSeat;
import com.concert.booking.domain.Seat;
import com.concert.booking.domain.SeatStatus;
import com.concert.booking.domain.User;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.PaymentRepository;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import com.concert.booking.service.stock.RedisStockService;
import com.concert.booking.service.stock.StockReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class StockReconciliationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RedisStockService redisStockService;
    @Autowired private StockReconciliationService stockReconciliationService;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationSeatRepository reservationSeatRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @Autowired private JwtProvider jwtProvider;

    @Test
    @DisplayName("stock 초기화는 DB AVAILABLE 좌석 수를 기준으로 생성한다")
    void initialize_uses_db_available_seat_count() {
        Scenario scenario = createScenarioWithStatuses();

        RedisStockService.StockSnapshot snapshot = redisStockService.initialize(scenario.scheduleId(), false);

        assertThat(snapshot.redisStock()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
    }

    @Test
    @DisplayName("stock 초기화는 overwrite=false면 기존 Redis 값을 보존하고 true면 DB 기준으로 덮어쓴다")
    void initialize_respects_overwrite_policy() {
        Scenario scenario = createScenarioWithStatuses();
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(scenario.scheduleId()), "99");

        RedisStockService.StockSnapshot preserved = redisStockService.initialize(scenario.scheduleId(), false);
        RedisStockService.StockSnapshot overwritten = redisStockService.initialize(scenario.scheduleId(), true);

        assertThat(preserved.redisStock()).isEqualTo(99);
        assertThat(overwritten.redisStock()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
    }

    @Test
    @DisplayName("reconciliation dry-run은 Redis stock이 DB보다 작거나 큰 경우와 schedule 불일치를 탐지한다")
    void reconciliation_detects_stock_and_schedule_mismatch() {
        Scenario scenario = createScenarioWithStatuses();
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(scenario.scheduleId()), "7");
        ConcertSchedule schedule = concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow();
        schedule.syncAvailableSeats(3);
        concertScheduleRepository.saveAndFlush(schedule);

        StockReconciliationService.ReconciliationResult highRedis =
                stockReconciliationService.reconcile(scenario.scheduleId(), false);

        assertThat(highRedis.availableSeatCount()).isEqualTo(1);
        assertThat(highRedis.heldSeatCount()).isEqualTo(1);
        assertThat(highRedis.reservedSeatCount()).isEqualTo(1);
        assertThat(highRedis.redisStock()).isEqualTo(7);
        assertThat(highRedis.scheduleAvailableSeats()).isEqualTo(3);
        assertThat(highRedis.mismatches())
                .contains("SCHEDULE_AVAILABLE_SEATS_MISMATCH", "REDIS_STOCK_MISMATCH");

        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(scenario.scheduleId()), "0");
        StockReconciliationService.ReconciliationResult lowRedis =
                stockReconciliationService.reconcile(scenario.scheduleId(), false);

        assertThat(lowRedis.redisStock()).isEqualTo(0);
        assertThat(lowRedis.mismatches()).contains("REDIS_STOCK_MISMATCH");
    }

    @Test
    @DisplayName("reconciliation repair는 DB AVAILABLE 기준으로 schedule과 Redis stock을 보정한다")
    void reconciliation_repair_aligns_schedule_and_redis_to_db_available_count() {
        Scenario scenario = createScenarioWithStatuses();
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(scenario.scheduleId()), "7");
        ConcertSchedule schedule = concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow();
        schedule.syncAvailableSeats(3);
        concertScheduleRepository.saveAndFlush(schedule);

        StockReconciliationService.ReconciliationResult repaired =
                stockReconciliationService.reconcile(scenario.scheduleId(), true);

        assertThat(repaired.repaired()).isTrue();
        assertThat(repaired.availableSeatCount()).isEqualTo(1);
        assertThat(repaired.scheduleAvailableSeats()).isEqualTo(1);
        assertThat(repaired.redisStock()).isEqualTo(1);
        assertThat(repaired.mismatches()).contains("SCHEDULE_AVAILABLE_SEATS_MISMATCH", "REDIS_STOCK_MISMATCH");
        assertThat(concertScheduleRepository.findById(scenario.scheduleId()).orElseThrow().getAvailableSeats()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("1");
    }

    @Test
    @DisplayName("admin reset은 예약/결제를 정리하고 좌석 50개와 Redis stock 50을 복구한다")
    void admin_reset_restores_available_seats_and_stock() throws Exception {
        Scenario scenario = createResetScenario();

        mockMvc.perform(post("/api/admin/reset")
                        .header("Authorization", "Bearer " + adminToken())
                        .param("scheduleId", String.valueOf(scenario.scheduleId())))
                .andExpect(status().isOk());

        assertThat(seatRepository.countByScheduleIdAndStatus(scenario.scheduleId(), SeatStatus.AVAILABLE)).isEqualTo(50);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("50");
        assertThat(reservationRepository.countByScheduleId(scenario.scheduleId())).isZero();
        assertThat(paymentRepository.countByScheduleId(scenario.scheduleId())).isZero();
    }

    private String adminToken() {
        User admin = userRepository.save(User.createAdmin(
                "stock-admin-" + System.nanoTime() + "@test.com",
                "password",
                "stock-admin"));
        return jwtProvider.createToken(admin.getId(), admin.getEmail());
    }

    private Scenario createScenarioWithStatuses() {
        Concert concert = Concert.create("Stock 정합성 콘서트 " + System.nanoTime(), "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(10), LocalTime.of(20, 0), 3);
        concertScheduleRepository.save(schedule);

        Seat available = Seat.create(schedule, "A", 1, 1, 100000);
        Seat held = Seat.create(schedule, "A", 1, 2, 100000);
        Seat reserved = Seat.create(schedule, "A", 1, 3, 100000);
        held.hold();
        reserved.hold();
        reserved.reserve();
        seatRepository.saveAll(List.of(available, held, reserved));
        redisTemplate.delete(RedisKeyUtil.stockKey(schedule.getId()));

        return new Scenario(schedule.getId());
    }

    private Scenario createResetScenario() {
        Concert concert = Concert.create("Stock reset 콘서트 " + System.nanoTime(), "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(11), LocalTime.of(20, 0), 50);
        concertScheduleRepository.save(schedule);

        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            seats.add(Seat.create(schedule, "A", 1, i, 100000));
        }
        seatRepository.saveAll(seats);
        redisTemplate.delete(RedisKeyUtil.stockKey(schedule.getId()));

        User user = User.create("stock-reset-" + System.nanoTime() + "@test.com", "password", "stock-reset");
        userRepository.save(user);

        Seat reservedSeat = seats.get(0);
        reservedSeat.hold();
        Reservation reservation = Reservation.create(user, schedule, reservedSeat.getPrice(), LocalDateTime.now().plusMinutes(5));
        reservationRepository.save(reservation);
        ReservationSeat reservationSeat = ReservationSeat.create(reservation, reservedSeat);
        reservationSeatRepository.save(reservationSeat);
        reservation.addReservationSeat(reservationSeat);
        Payment payment = Payment.create(reservation, reservedSeat.getPrice(), "stock-reset-payment-" + System.nanoTime());
        paymentRepository.save(payment);
        reservedSeat.reserve();
        schedule.decreaseAvailableSeats(1);
        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(schedule.getId()), "3");
        redisTemplate.opsForValue().set(RedisKeyUtil.queueKey(schedule.getId()), "temporary");
        redisTemplate.opsForValue().set(RedisKeyUtil.tokenKey(user.getId(), schedule.getId()), "temporary-token");

        return new Scenario(schedule.getId());
    }

    private record Scenario(Long scheduleId) {
    }
}
