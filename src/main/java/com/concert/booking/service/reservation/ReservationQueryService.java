package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.ForbiddenException;
import com.concert.booking.common.exception.ReservationNotFoundException;
import com.concert.booking.domain.Reservation;
import com.concert.booking.dto.concert.SeatResponse;
import com.concert.booking.dto.reservation.ReservationDetailResponse;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReservationQueryService {

    private final ReservationRepository reservationRepository;

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
    public List<ReservationResponse> getMyReservations(Long userId) {
        return reservationRepository.findByUserId(userId).stream()
                .map(ReservationResponse::from)
                .toList();
    }
}
