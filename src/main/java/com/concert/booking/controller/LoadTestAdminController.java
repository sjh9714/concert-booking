package com.concert.booking.controller;

import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.domain.Seat;
import com.concert.booking.domain.SeatStatus;
import com.concert.booking.domain.User;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.PaymentRepository;
import com.concert.booking.repository.ReservationIdempotencyKeyRepository;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import com.concert.booking.service.reservation.ReservationExpirationScheduler;
import com.concert.booking.service.stock.RedisStockService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@Profile("!prod")
@RequestMapping("/api/admin/load-test")
@RequiredArgsConstructor
public class LoadTestAdminController {

    private static final String LOAD_TEST_EMAIL_PREFIX = "loadtest-user-";
    private static final String LOAD_TEST_EMAIL_DOMAIN = "@k6.local";
    private static final String LOAD_TEST_PASSWORD = "password123";

    private final PaymentRepository paymentRepository;
    private final ReservationIdempotencyKeyRepository reservationIdempotencyKeyRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final UserRepository userRepository;
    private final ReservationExpirationScheduler reservationExpirationScheduler;
    private final RedisStockService redisStockService;
    private final RedisTemplate<String, String> redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;

    @PostMapping("/reset")
    @Transactional
    public ResponseEntity<LoadTestResetResponse> reset(
            @RequestParam(defaultValue = "1") Long scheduleId,
            @RequestParam(defaultValue = "200") int userCount) {
        if (userCount <= 0) {
            throw new IllegalArgumentException("userCount는 1 이상이어야 합니다.");
        }

        ConcertSchedule schedule = concertScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다."));

        log.info("load-test fixture reset 시작: scheduleId={}, userCount={}", scheduleId, userCount);

        paymentRepository.deleteByScheduleId(scheduleId);
        reservationIdempotencyKeyRepository.deleteByScheduleId(scheduleId);
        reservationSeatRepository.deleteByScheduleId(scheduleId);
        reservationRepository.deleteByScheduleId(scheduleId);

        seatRepository.resetSeatsByScheduleId(scheduleId);
        concertScheduleRepository.resetAvailableSeats(scheduleId);
        entityManager.flush();
        entityManager.clear();

        resetRedis(scheduleId);
        RedisStockService.StockSnapshot stockSnapshot = redisStockService.initialize(scheduleId, true);
        ensureLoadTestUsers(userCount);

        ConcertSchedule reloadedSchedule = concertScheduleRepository.findById(scheduleId).orElseThrow();
        long availableSeatCount = seatRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.AVAILABLE);
        Integer redisStock = redisStockService.readRedisStock(scheduleId);

        log.info("load-test fixture reset 완료: scheduleId={}, availableSeats={}, redisStock={}",
                scheduleId, availableSeatCount, redisStock);

        return ResponseEntity.ok(new LoadTestResetResponse(
                reloadedSchedule.getConcert().getId(),
                scheduleId,
                userCount,
                LOAD_TEST_PASSWORD,
                Math.toIntExact(availableSeatCount),
                redisStock != null ? redisStock : stockSnapshot.redisStock()
        ));
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public ResponseEntity<LoadTestSummaryResponse> summary(
            @RequestParam(defaultValue = "1") Long scheduleId) {
        ConcertSchedule schedule = concertScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다."));

        long availableSeatCount = seatRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.AVAILABLE);
        long heldSeatCount = seatRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.HELD);
        long reservedSeatCount = seatRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.RESERVED);

        return ResponseEntity.ok(new LoadTestSummaryResponse(
                scheduleId,
                schedule.getTotalSeats(),
                availableSeatCount,
                heldSeatCount,
                reservedSeatCount,
                schedule.getAvailableSeats(),
                redisStockService.readRedisStock(scheduleId),
                reservationRepository.countByScheduleId(scheduleId),
                countReservations(scheduleId, ReservationStatus.PENDING),
                countReservations(scheduleId, ReservationStatus.CONFIRMED),
                countReservations(scheduleId, ReservationStatus.CANCELLED),
                countReservations(scheduleId, ReservationStatus.EXPIRED),
                paymentRepository.countByScheduleId(scheduleId),
                countDuplicateSeatReservations(scheduleId),
                countDuplicatePayments(scheduleId)
        ));
    }

    @PostMapping("/reservations/{reservationId}/expire")
    public ResponseEntity<LoadTestExpireResponse> expireReservation(@PathVariable Long reservationId) {
        boolean expired = reservationExpirationScheduler.expireReservation(
                reservationId,
                LocalDateTime.now().plusMinutes(10)
        );
        String status = reservationRepository.findById(reservationId)
                .map(Reservation::getStatus)
                .map(Enum::name)
                .orElse("NOT_FOUND");

        return ResponseEntity.ok(new LoadTestExpireResponse(reservationId, expired, status));
    }

    @PostMapping("/tokens/expire")
    public ResponseEntity<LoadTestTokenExpireResponse> expireQueueToken(
            @RequestParam Long userId,
            @RequestParam Long scheduleId) {
        Boolean tokenDeleted = redisTemplate.delete(RedisKeyUtil.tokenKey(userId, scheduleId));
        Boolean inflightDeleted = redisTemplate.delete(RedisKeyUtil.tokenInFlightKey(userId, scheduleId));

        return ResponseEntity.ok(new LoadTestTokenExpireResponse(
                userId,
                scheduleId,
                Boolean.TRUE.equals(tokenDeleted),
                Boolean.TRUE.equals(inflightDeleted)
        ));
    }

    private void ensureLoadTestUsers(int userCount) {
        for (int i = 0; i < userCount; i++) {
            String email = LOAD_TEST_EMAIL_PREFIX + i + LOAD_TEST_EMAIL_DOMAIN;
            if (userRepository.findByEmail(email).isEmpty()) {
                userRepository.save(User.create(
                        email,
                        passwordEncoder.encode(LOAD_TEST_PASSWORD),
                        LOAD_TEST_EMAIL_PREFIX + i
                ));
            }
        }
    }

    private void resetRedis(Long scheduleId) {
        redisTemplate.delete(RedisKeyUtil.stockKey(scheduleId));
        redisTemplate.delete(RedisKeyUtil.queueKey(scheduleId));
        redisTemplate.delete(RedisKeyUtil.activeKey(scheduleId));

        List<Seat> seats = seatRepository.findByScheduleId(scheduleId);
        for (Seat seat : seats) {
            redisTemplate.delete(RedisKeyUtil.seatHoldKey(seat.getId()));
        }

        deleteByPattern("token:queue:*:" + scheduleId);
        deleteByPattern("token:queue:*:" + scheduleId + ":inflight");
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private long countReservations(Long scheduleId, ReservationStatus status) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM reservations
                WHERE schedule_id = ? AND status = ?
                """, Long.class, scheduleId, status.name());
    }

    private long countDuplicateSeatReservations(Long scheduleId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT rs.seat_id
                    FROM reservation_seats rs
                    JOIN reservations r ON r.id = rs.reservation_id
                    WHERE r.schedule_id = ?
                    GROUP BY rs.seat_id
                    HAVING COUNT(*) > 1
                ) duplicated
                """, Long.class, scheduleId);
    }

    private long countDuplicatePayments(Long scheduleId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT p.reservation_id
                    FROM payments p
                    JOIN reservations r ON r.id = p.reservation_id
                    WHERE r.schedule_id = ?
                    GROUP BY p.reservation_id
                    HAVING COUNT(*) > 1
                ) duplicated
                """, Long.class, scheduleId);
    }

    public record LoadTestResetResponse(
            Long concertId,
            Long scheduleId,
            int userCount,
            String password,
            int availableSeatCount,
            Integer redisStock
    ) {
    }

    public record LoadTestSummaryResponse(
            Long scheduleId,
            int totalSeats,
            long availableSeatCount,
            long heldSeatCount,
            long reservedSeatCount,
            int scheduleAvailableSeats,
            Integer redisStock,
            long reservationCount,
            long pendingReservationCount,
            long confirmedReservationCount,
            long cancelledReservationCount,
            long expiredReservationCount,
            long paymentCount,
            long duplicateSeatReservationCount,
            long duplicatePaymentCount
    ) {
    }

    public record LoadTestExpireResponse(
            Long reservationId,
            boolean expired,
            String status
    ) {
    }

    public record LoadTestTokenExpireResponse(
            Long userId,
            Long scheduleId,
            boolean tokenDeleted,
            boolean inflightDeleted
    ) {
    }
}
