package com.concert.booking.integration;

import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Payment;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationIdempotencyKey;
import com.concert.booking.domain.ReservationSeat;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.domain.Seat;
import com.concert.booking.domain.SeatStatus;
import com.concert.booking.domain.User;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.PaymentRepository;
import com.concert.booking.repository.ReservationIdempotencyKeyRepository;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class LoadTestAdminControllerIntegrationTest {

    private static final String LOAD_TEST_PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationSeatRepository reservationSeatRepository;
    @Autowired private ReservationIdempotencyKeyRepository reservationIdempotencyKeyRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("load-test reset은 같은 fixture 상태: 좌석 50개, Redis stock 50, 예약/결제/큐 키 정리")
    void resetLoadTestData_restores_reproducible_fixture() throws Exception {
        Scenario scenario = createDirtyLoadTestScenario();

        mockMvc.perform(post("/api/admin/load-test/reset")
                        .param("scheduleId", String.valueOf(scenario.scheduleId()))
                        .param("userCount", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concertId").value(scenario.concertId()))
                .andExpect(jsonPath("$.scheduleId").value(scenario.scheduleId()))
                .andExpect(jsonPath("$.userCount").value(3))
                .andExpect(jsonPath("$.password").value(LOAD_TEST_PASSWORD))
                .andExpect(jsonPath("$.availableSeatCount").value(50))
                .andExpect(jsonPath("$.redisStock").value(50));

        assertThat(seatRepository.countByScheduleIdAndStatus(scenario.scheduleId(), SeatStatus.AVAILABLE)).isEqualTo(50);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(scenario.scheduleId()))).isEqualTo("50");
        assertThat(reservationRepository.countByScheduleId(scenario.scheduleId())).isZero();
        assertThat(paymentRepository.countByScheduleId(scenario.scheduleId())).isZero();
        assertThat(countReservationSeats(scenario.scheduleId())).isZero();
        assertThat(countReservationIdempotencyKeys(scenario.scheduleId())).isZero();

        assertThat(redisTemplate.hasKey(RedisKeyUtil.queueKey(scenario.scheduleId()))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeyUtil.activeKey(scenario.scheduleId()))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeyUtil.tokenKey(scenario.userId(), scenario.scheduleId()))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeyUtil.tokenInFlightKey(scenario.userId(), scenario.scheduleId()))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeyUtil.seatHoldKey(scenario.firstSeatId()))).isFalse();

        for (int i = 0; i < 3; i++) {
            User user = userRepository.findByEmail("loadtest-user-" + i + "@k6.local").orElseThrow();
            assertThat(passwordEncoder.matches(LOAD_TEST_PASSWORD, user.getPassword())).isTrue();
        }
    }

    @Test
    @DisplayName("load-test summary는 좌석/예약/결제/중복 의심 지표와 Redis stock을 반환한다")
    void summary_returns_load_test_counts() throws Exception {
        Scenario scenario = createDirtyLoadTestScenario();
        mockMvc.perform(post("/api/admin/load-test/reset")
                        .param("scheduleId", String.valueOf(scenario.scheduleId()))
                        .param("userCount", "2"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/load-test/summary")
                        .param("scheduleId", String.valueOf(scenario.scheduleId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(scenario.scheduleId()))
                .andExpect(jsonPath("$.totalSeats").value(50))
                .andExpect(jsonPath("$.availableSeatCount").value(50))
                .andExpect(jsonPath("$.heldSeatCount").value(0))
                .andExpect(jsonPath("$.reservedSeatCount").value(0))
                .andExpect(jsonPath("$.scheduleAvailableSeats").value(50))
                .andExpect(jsonPath("$.redisStock").value(50))
                .andExpect(jsonPath("$.reservationCount").value(0))
                .andExpect(jsonPath("$.paymentCount").value(0))
                .andExpect(jsonPath("$.duplicateSeatReservationCount").value(0))
                .andExpect(jsonPath("$.duplicatePaymentCount").value(0));
    }

    @Test
    @DisplayName("load-test expire와 token expire utility는 race/abuse 시나리오를 재현할 수 있게 한다")
    void expireUtilities_support_race_and_token_abuse_scenarios() throws Exception {
        Scenario scenario = createDirtyLoadTestScenario();

        mockMvc.perform(post("/api/admin/load-test/reservations/{id}/expire", scenario.reservationId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(scenario.reservationId()))
                .andExpect(jsonPath("$.expired").value(true))
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        assertThat(reservationRepository.findById(scenario.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);

        redisTemplate.opsForValue().set(RedisKeyUtil.tokenKey(scenario.userId(), scenario.scheduleId()), "token");
        redisTemplate.opsForValue().set(RedisKeyUtil.tokenInFlightKey(scenario.userId(), scenario.scheduleId()), "1");

        mockMvc.perform(post("/api/admin/load-test/tokens/expire")
                        .param("userId", String.valueOf(scenario.userId()))
                        .param("scheduleId", String.valueOf(scenario.scheduleId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(scenario.userId()))
                .andExpect(jsonPath("$.scheduleId").value(scenario.scheduleId()))
                .andExpect(jsonPath("$.tokenDeleted").value(true))
                .andExpect(jsonPath("$.inflightDeleted").value(true));

        assertThat(redisTemplate.hasKey(RedisKeyUtil.tokenKey(scenario.userId(), scenario.scheduleId()))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeyUtil.tokenInFlightKey(scenario.userId(), scenario.scheduleId()))).isFalse();
    }

    private Scenario createDirtyLoadTestScenario() {
        Concert concert = Concert.create("Load test concert " + System.nanoTime(), "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(14), LocalTime.of(20, 0), 50);
        concertScheduleRepository.save(schedule);

        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            seats.add(Seat.create(schedule, "A", 1, i, 100000));
        }
        seatRepository.saveAll(seats);

        User user = userRepository.save(User.create("load-test-dirty-" + System.nanoTime() + "@test.com", "password", "dirty"));

        Seat firstSeat = seats.get(0);
        firstSeat.hold();
        schedule.decreaseAvailableSeats(1);

        Reservation reservation = Reservation.create(user, schedule, firstSeat.getPrice(), LocalDateTime.now().plusMinutes(5));
        reservationRepository.save(reservation);
        ReservationSeat reservationSeat = ReservationSeat.create(reservation, firstSeat);
        reservationSeatRepository.save(reservationSeat);
        reservation.addReservationSeat(reservationSeat);
        paymentRepository.save(Payment.create(reservation, firstSeat.getPrice(), "dirty-payment-key"));
        reservationIdempotencyKeyRepository.save(ReservationIdempotencyKey.create(
                user.getId(),
                schedule.getId(),
                "dirty-reservation-key",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ));

        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(schedule.getId()), "3");
        redisTemplate.opsForZSet().add(RedisKeyUtil.queueKey(schedule.getId()), String.valueOf(user.getId()), 1.0);
        redisTemplate.opsForSet().add(RedisKeyUtil.activeKey(schedule.getId()), String.valueOf(user.getId()));
        redisTemplate.opsForValue().set(RedisKeyUtil.tokenKey(user.getId(), schedule.getId()), "token");
        redisTemplate.opsForValue().set(RedisKeyUtil.tokenInFlightKey(user.getId(), schedule.getId()), "1");
        redisTemplate.opsForValue().set(RedisKeyUtil.seatHoldKey(firstSeat.getId()), String.valueOf(reservation.getId()));

        return new Scenario(concert.getId(), schedule.getId(), user.getId(), firstSeat.getId(), reservation.getId());
    }

    private long countReservationSeats(Long scheduleId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM reservation_seats rs
                JOIN reservations r ON r.id = rs.reservation_id
                WHERE r.schedule_id = ?
                """, Long.class, scheduleId);
    }

    private long countReservationIdempotencyKeys(Long scheduleId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM reservation_idempotency_keys
                WHERE schedule_id = ?
                """, Long.class, scheduleId);
    }

    private record Scenario(Long concertId, Long scheduleId, Long userId, Long firstSeatId, Long reservationId) {
    }
}
