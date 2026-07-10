package com.concert.booking.service.reservation;

import com.concert.booking.dto.reservation.ReservationDetailResponse;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.dto.reservation.ReservationSummaryResponse;

import java.util.List;

public interface ReservationService {

    ReservationResponse reserve(Long userId, ReservationRequest request, String idempotencyKey);

    ReservationDetailResponse getReservation(Long userId, Long reservationId);

    List<ReservationSummaryResponse> getMyReservations(Long userId);

    void cancelReservation(Long userId, Long reservationId);
}
