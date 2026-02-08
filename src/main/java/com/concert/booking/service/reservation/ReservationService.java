package com.concert.booking.service.reservation;

import com.concert.booking.dto.reservation.ReservationDetailResponse;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;

import java.util.List;

public interface ReservationService {

    ReservationResponse reserve(Long userId, ReservationRequest request);

    ReservationDetailResponse getReservation(Long reservationId);

    List<ReservationResponse> getMyReservations(Long userId);

    void cancelReservation(Long userId, Long reservationId);
}
