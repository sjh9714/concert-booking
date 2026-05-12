package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationSeat;
import com.concert.booking.domain.Seat;
import com.concert.booking.domain.User;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import com.concert.booking.service.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ReservationCreationService {

    private static final int HOLD_MINUTES = 5;
    private static final int SEAT_HOLD_TTL_SECONDS = 300;

    private final UserRepository userRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final ReservationIdempotencyService reservationIdempotencyService;
    private final OutboxEventService outboxEventService;
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationResponse create(ReservationCommand command, ReservationCreationMode mode) {
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        ConcertSchedule schedule = findSchedule(command.request().scheduleId(), mode);
        List<Seat> seats = findAvailableSeats(command, mode);
        if (seats.size() != command.sortedSeatIds().size()) {
            throw new SeatNotAvailableException("선택한 좌석 중 이미 예매된 좌석이 있습니다.");
        }

        seats.forEach(Seat::hold);

        int totalAmount = seats.stream().mapToInt(Seat::getPrice).sum();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(HOLD_MINUTES);
        Reservation reservation = Reservation.create(user, schedule, totalAmount, expiresAt);
        reservationRepository.save(reservation);

        for (Seat seat : seats) {
            ReservationSeat reservationSeat = ReservationSeat.create(reservation, seat);
            reservationSeatRepository.save(reservationSeat);
            reservation.addReservationSeat(reservationSeat);
        }

        schedule.decreaseAvailableSeats(seats.size());
        reservationIdempotencyService.complete(command.claimId(), reservation);
        outboxEventService.saveReservationCreated(reservation);

        if (mode == ReservationCreationMode.DISTRIBUTED) {
            for (Seat seat : seats) {
                redisTemplate.opsForValue().set(
                        RedisKeyUtil.seatHoldKey(seat.getId()),
                        String.valueOf(reservation.getId()),
                        SEAT_HOLD_TTL_SECONDS, TimeUnit.SECONDS
                );
            }
        }

        return ReservationResponse.from(reservation);
    }

    private ConcertSchedule findSchedule(Long scheduleId, ReservationCreationMode mode) {
        return switch (mode) {
            case PESSIMISTIC, DISTRIBUTED -> concertScheduleRepository.findByIdForUpdate(scheduleId)
                    .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다."));
            case OPTIMISTIC -> concertScheduleRepository.findById(scheduleId)
                    .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다."));
        };
    }

    private List<Seat> findAvailableSeats(ReservationCommand command, ReservationCreationMode mode) {
        return switch (mode) {
            case PESSIMISTIC -> seatRepository.findAllByScheduleIdAndIdInAndAvailableForUpdate(
                    command.request().scheduleId(),
                    command.sortedSeatIds()
            );
            case OPTIMISTIC, DISTRIBUTED -> seatRepository.findAllByScheduleIdAndIdInAndAvailable(
                    command.request().scheduleId(),
                    command.sortedSeatIds()
            );
        };
    }
}
