package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.ForbiddenException;
import com.concert.booking.common.exception.ReservationNotFoundException;
import com.concert.booking.domain.Reservation;
import com.concert.booking.dto.concert.SeatResponse;
import com.concert.booking.dto.reservation.ReservationDetailResponse;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.dto.reservation.ReservationSummaryResponse;
import com.concert.booking.repository.PaymentRepository;
import com.concert.booking.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReservationQueryService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public ReservationDetailResponse getReservation(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("예매를 찾을 수 없습니다."));

        if (!reservation.getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인의 예매만 조회할 수 있습니다.");
        }

        List<SeatResponse> seats = reservation.getReservationSeats().stream()
                .map(rs -> SeatResponse.from(rs.getSeat()))
                .toList();

        return ReservationDetailResponse.from(reservation, seats);
    }

    @Transactional(readOnly = true)
    public List<ReservationSummaryResponse> getMyReservations(Long userId) {
        List<Reservation> reservations = reservationRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (reservations.isEmpty()) {
            return List.of();
        }
        Map<Long, com.concert.booking.domain.Payment> payments = paymentRepository
                .findByReservationIdIn(reservations.stream().map(Reservation::getId).toList())
                .stream()
                .collect(Collectors.toMap(payment -> payment.getReservation().getId(), Function.identity()));

        return reservations.stream()
                .map(reservation -> ReservationSummaryResponse.from(
                        reservation,
                        payments.containsKey(reservation.getId())
                                ? payments.get(reservation.getId()).getStatus()
                                : null))
                .toList();
    }
}
