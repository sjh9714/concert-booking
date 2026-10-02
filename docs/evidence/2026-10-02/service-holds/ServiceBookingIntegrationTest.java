package com.concert.booking.integration;

import com.concert.booking.config.ServicePostgresConfig;
import com.concert.booking.domain.*;
import com.concert.booking.dto.payment.PaymentRequest;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.repository.*;
import com.concert.booking.service.concert.ConcertService;
import com.concert.booking.service.payment.PaymentService;
import com.concert.booking.service.reservation.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"test", "service"})
@Import(ServicePostgresConfig.class)
class ServiceBookingIntegrationTest {
    @Autowired ReservationService reservations;
    @Autowired PaymentService payments;
    @Autowired ReservationExpirationScheduler expiration;
    @Autowired SeatReleaseService release;
    @Autowired ConcertService concerts;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concertRepository;
    @Autowired ConcertScheduleRepository schedules;
    @Autowired SeatRepository seats;
    @Autowired ReservationRepository reservationRepository;
    @Autowired JdbcTemplate jdbc;

    record Scenario(Long concert, Long schedule, Long user, List<Long> seats) {}

    private Scenario scenario(int size) {
        var concert = concertRepository.save(Concert.create("서비스 검증", "합성 공연", "테스트 홀", "데모 밴드"));
        var schedule = schedules.save(ConcertSchedule.create(concert, LocalDate.now().plusDays(7), LocalTime.NOON, size));
        var seatRows = seats.saveAll(IntStream.rangeClosed(1, size).mapToObj(i -> Seat.create(schedule, "A", 1, i, 10000)).toList());
        var user = users.save(User.create(UUID.randomUUID() + "@example.com", "unused-in-service-test", "테스트"));
        return new Scenario(concert.getId(), schedule.getId(), user.getId(), seatRows.stream().map(Seat::getId).toList());
    }

    private Long hold(Scenario s, List<Long> seatIds, String key) {
        return reservations.reserve(s.user(), new ReservationRequest(s.schedule(), seatIds, null), key).id();
    }

    @Test
    void tokenlessHoldUsesSeatsAsTruthAndDoesNotUpdateSharedScheduleCounter() {
        var s = scenario(2);
        hold(s, List.of(s.seats().getFirst()), UUID.randomUUID().toString());
        assertThat(concerts.getSchedules(s.concert()).getFirst().availableSeats()).isEqualTo(1);
        assertThat(concerts.getConcert(s.concert()).availableSeats()).isEqualTo(1);
        assertThat(schedules.findById(s.schedule()).orElseThrow().getAvailableSeats()).isEqualTo(2);
    }

    @Test
    void cancellationIsCommittedTogetherWithReleaseAndOldReleaseDoesNotTouchNewOwner() {
        var s = scenario(1);
        Long first = hold(s, s.seats(), "first");
        reservations.cancelReservation(s.user(), first);
        assertThat(seats.findById(s.seats().getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        Long second = hold(s, s.seats(), "second");
        assertThat(release.releaseHeldSeats(first, "redelivery").releasedCount()).isZero();
        assertThat(seats.findById(s.seats().getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(jdbc.queryForObject("SELECT current_reservation_id FROM seats WHERE id = ?", Long.class, s.seats().getFirst())).isEqualTo(second);
    }

    @Test
    void mixedAvailableAndHeldSelectionRollsBackEverySeat() {
        var s = scenario(2);
        hold(s, List.of(s.seats().get(1)), "taken");
        assertThatThrownBy(() -> hold(s, s.seats(), "both")).isInstanceOf(com.concert.booking.common.exception.SeatNotAvailableException.class);
        assertThat(seats.findById(s.seats().getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE schedule_id = ?", Long.class, s.schedule())).isEqualTo(1);
    }

    @Test
    void retriesReturnTheSameReservationAndPayment() {
        var s = scenario(2);
        Long id = hold(s, s.seats(), "same-request");
        assertThat(hold(s, s.seats(), "same-request")).isEqualTo(id);
        var payment = payments.pay(s.user(), new PaymentRequest(id), "same-payment");
        assertThat(payments.pay(s.user(), new PaymentRequest(id), "same-payment").id()).isEqualTo(payment.id());
        assertThat(reservationRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(seats.findAllById(s.seats())).allSatisfy(seat -> assertThat(seat.getStatus()).isEqualTo(SeatStatus.RESERVED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE reservation_id = ?", Long.class, id)).isEqualTo(1);
    }

    @Test
    void expirationReleasesBeforeReturningAndPaymentCannotReviveIt() {
        var s = scenario(1);
        Long id = hold(s, s.seats(), "expire");
        assertThat(expiration.expireReservation(id, LocalDateTime.now().plusMinutes(6))).isTrue();
        assertThat(expiration.expireReservation(id, LocalDateTime.now().plusMinutes(6))).isFalse();
        assertThat(seats.findById(s.seats().getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThatThrownBy(() -> payments.pay(s.user(), new PaymentRequest(id), "late-payment")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void paymentAndExpirySerializeOneStateTransition() throws Exception {
        var s = scenario(1);
        Long id = hold(s, s.seats(), "race");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var pay = pool.submit(() -> { start.await(); try { payments.pay(s.user(), new PaymentRequest(id), "race-pay"); return true; } catch (com.concert.booking.common.exception.InvalidReservationStateException e) { return false; } });
            var expire = pool.submit(() -> { start.await(); return expiration.expireReservation(id, LocalDateTime.now().plusMinutes(6)); });
            start.countDown();
            assertThat(List.of(pay.get(10, TimeUnit.SECONDS), expire.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        var state = reservationRepository.findById(id).orElseThrow().getStatus();
        assertThat(seats.findById(s.seats().getFirst()).orElseThrow().getStatus())
            .isEqualTo(state == ReservationStatus.CONFIRMED ? SeatStatus.RESERVED : SeatStatus.AVAILABLE);
    }

    @Test
    void simultaneousHoldsHaveOneSuccessForSameSeatAndAllForDifferentSeats() throws Exception {
        for (boolean sameSeat : List.of(true, false)) {
            var s = scenario(8);
            var start = new CountDownLatch(1);
            record Result(boolean success, long nanos) {}
            List<Result> results;
            try (var pool = Executors.newFixedThreadPool(8)) {
                var futures = IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                    start.await(); long began = System.nanoTime();
                    try {
                        hold(s, List.of(s.seats().get(sameSeat ? 0 : i)), "parallel-" + i);
                        return new Result(true, System.nanoTime() - began);
                    } catch (com.concert.booking.common.exception.SeatNotAvailableException expectedConflict) {
                        return new Result(false, System.nanoTime() - began);
                    }
                })).toList();
                start.countDown();
                results = new java.util.ArrayList<>();
                for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            }
            long successes = results.stream().filter(Result::success).count();
            assertThat(successes).isEqualTo(sameSeat ? 1 : 8);
            assertThat(seats.countByScheduleIdAndStatus(s.schedule(), SeatStatus.HELD)).isEqualTo(successes);
            Path path = Path.of("build/evidence/service-holds"); Files.createDirectories(path);
            StringBuilder csv = new StringBuilder("request,success,elapsed_ns\n");
            for (int i = 0; i < results.size(); i++) csv.append(i).append(',').append(results.get(i).success()).append(',').append(results.get(i).nanos()).append('\n');
            Files.writeString(path.resolve(sameSeat ? "same-seat.csv" : "different-seats.csv"), csv);
        }
    }
}
