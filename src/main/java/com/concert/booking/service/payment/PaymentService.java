package com.concert.booking.service.payment;

import com.concert.booking.common.exception.BadRequestException;
import com.concert.booking.common.exception.ConflictException;
import com.concert.booking.common.exception.InvalidReservationStateException;
import com.concert.booking.common.exception.PaymentException;
import com.concert.booking.common.exception.ForbiddenException;
import com.concert.booking.common.exception.PaymentNotFoundException;
import com.concert.booking.common.exception.ReservationNotFoundException;
import com.concert.booking.domain.Payment;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationSeat;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.dto.payment.PaymentRequest;
import com.concert.booking.dto.payment.PaymentResponse;
import com.concert.booking.repository.PaymentRepository;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import com.concert.booking.service.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxEventService outboxEventService;

    @Transactional
    public PaymentResponse pay(Long userId, PaymentRequest request, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);

        Reservation reservation = reservationRepository.findByIdForUpdate(request.reservationId())
                .orElseThrow(() -> new ReservationNotFoundException("예매를 찾을 수 없습니다."));

        // 본인 예매 확인
        if (!reservation.getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인의 예매만 결제할 수 있습니다.");
        }

        var existingPaymentWithSameKey =
                paymentRepository.findByReservationIdAndIdempotencyKey(reservation.getId(), idempotencyKey);
        if (existingPaymentWithSameKey.isPresent()) {
            return PaymentResponse.from(existingPaymentWithSameKey.get());
        }

        paymentRepository.findByReservationId(reservation.getId())
                .ifPresent(existingPayment -> {
                    throw new ConflictException("이미 결제된 예매입니다.");
                });

        return processPayment(reservation, idempotencyKey);
    }

    private PaymentResponse processPayment(Reservation reservation, String idempotencyKey) {
        // 상태 확인
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new InvalidReservationStateException("대기 중인 예매만 결제할 수 있습니다.");
        }

        // 만료 확인
        if (reservation.isExpired()) {
            throw new PaymentException("예매가 만료되었습니다. 다시 예매해주세요.");
        }

        // 결제 생성 (mock PG — 즉시 COMPLETED)
        Payment payment = Payment.create(reservation, reservation.getTotalAmount(), idempotencyKey);
        paymentRepository.save(payment);

        // 예매 확정
        reservation.confirm();

        // 좌석 상태: HELD → RESERVED
        List<ReservationSeat> reservationSeats = reservationSeatRepository.findByReservationId(reservation.getId());
        for (ReservationSeat rs : reservationSeats) {
            rs.getSeat().reserve();
        }

        outboxEventService.saveReservationConfirmed(reservation);

        return PaymentResponse.from(payment);
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BadRequestException("Idempotency-Key header는 필수입니다.");
        }
        if (idempotencyKey.length() > 120) {
            throw new BadRequestException("Idempotency-Key는 최대 120자까지 허용됩니다.");
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long userId, Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("결제 정보를 찾을 수 없습니다."));
        if (!payment.getReservation().getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인의 결제만 조회할 수 있습니다.");
        }
        return PaymentResponse.from(payment);
    }
}
