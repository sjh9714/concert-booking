package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.InvalidReservationStateException;
import com.concert.booking.common.exception.ReservationNotFoundException;
import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.domain.*;
import com.concert.booking.dto.concert.SeatResponse;
import com.concert.booking.dto.reservation.ReservationDetailResponse;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Primary
@RequiredArgsConstructor
public class PessimisticLockReservationService implements ReservationService {

    private static final int HOLD_MINUTES = 5;

    private final UserRepository userRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;

    @Override
    @Transactional
    public ReservationResponse reserve(Long userId, ReservationRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        ConcertSchedule schedule = concertScheduleRepository.findById(request.scheduleId())
                .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다."));

        // 데드락 방지: 좌석 ID 정렬
        List<Long> sortedSeatIds = request.seatIds().stream().sorted().toList();

        // 비관적 락으로 좌석 조회 (SELECT FOR UPDATE)
        List<Seat> seats = seatRepository.findAllByIdInAndAvailableForUpdate(sortedSeatIds);

        // All-or-Nothing: 요청한 좌석 수와 조회된 좌석 수 비교
        if (seats.size() != sortedSeatIds.size()) {
            throw new SeatNotAvailableException("선택한 좌석 중 이미 예매된 좌석이 있습니다.");
        }

        // 좌석 HOLD 처리
        seats.forEach(Seat::hold);

        // 총 금액 계산
        int totalAmount = seats.stream().mapToInt(Seat::getPrice).sum();

        // 예매 생성
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(HOLD_MINUTES);
        Reservation reservation = Reservation.create(user, schedule, totalAmount, expiresAt);
        reservationRepository.save(reservation);

        // 예매-좌석 매핑
        for (Seat seat : seats) {
            ReservationSeat rs = ReservationSeat.create(reservation, seat);
            reservationSeatRepository.save(rs);
            reservation.addReservationSeat(rs);
        }

        // 잔여 좌석 감소
        schedule.decreaseAvailableSeats(seats.size());

        return ReservationResponse.from(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationDetailResponse getReservation(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("예매를 찾을 수 없습니다."));

        List<SeatResponse> seats = reservation.getReservationSeats().stream()
                .map(rs -> SeatResponse.from(rs.getSeat()))
                .toList();

        return ReservationDetailResponse.from(reservation, seats);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationResponse> getMyReservations(Long userId) {
        return reservationRepository.findByUserId(userId).stream()
                .map(ReservationResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public void cancelReservation(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("예매를 찾을 수 없습니다."));

        if (!reservation.getUser().getId().equals(userId)) {
            throw new InvalidReservationStateException("본인의 예매만 취소할 수 있습니다.");
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new InvalidReservationStateException("대기 중인 예매만 취소할 수 있습니다.");
        }

        // 예매 취소
        reservation.cancel();

        // 좌석 반환
        List<ReservationSeat> reservationSeats = reservationSeatRepository.findByReservationId(reservationId);
        for (ReservationSeat rs : reservationSeats) {
            rs.getSeat().release();
        }

        // 잔여 좌석 복원
        reservation.getSchedule().increaseAvailableSeats(reservationSeats.size());
    }
}
