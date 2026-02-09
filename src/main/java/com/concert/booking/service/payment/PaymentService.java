package com.concert.booking.service.payment;

import com.concert.booking.common.exception.InvalidReservationStateException;
import com.concert.booking.common.exception.PaymentException;
import com.concert.booking.common.exception.ReservationNotFoundException;
import com.concert.booking.domain.Payment;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationSeat;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.dto.payment.PaymentRequest;
import com.concert.booking.dto.payment.PaymentResponse;
import com.concert.booking.event.ReservationCompletedEvent;
import com.concert.booking.repository.PaymentRepository;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public PaymentResponse pay(Long userId, PaymentRequest request) {
        Reservation reservation = reservationRepository.findById(request.reservationId())
                .orElseThrow(() -> new ReservationNotFoundException("예매를 찾을 수 없습니다."));

        // 본인 예매 확인
        if (!reservation.getUser().getId().equals(userId)) {
            throw new InvalidReservationStateException("본인의 예매만 결제할 수 있습니다.");
        }

        // 상태 확인
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new InvalidReservationStateException("대기 중인 예매만 결제할 수 있습니다.");
        }

        // 만료 확인
        if (reservation.isExpired()) {
            throw new PaymentException("예매가 만료되었습니다. 다시 예매해주세요.");
        }

        // 결제 생성 (mock PG — 즉시 COMPLETED)
        Payment payment = Payment.create(reservation, reservation.getTotalAmount());
        paymentRepository.save(payment);

        // 예매 확정
        reservation.confirm();

        // 좌석 상태: HELD → RESERVED
        List<ReservationSeat> reservationSeats = reservationSeatRepository.findByReservationId(reservation.getId());
        for (ReservationSeat rs : reservationSeats) {
            rs.getSeat().reserve();
        }

        // Kafka 이벤트 발행: 예매 완료
        try {
            ReservationCompletedEvent event = new ReservationCompletedEvent(
                    reservation.getId(),
                    reservation.getUser().getId(),
                    reservation.getSchedule().getId(),
                    reservation.getTotalAmount(),
                    LocalDateTime.now()
            );
            kafkaTemplate.send("reservation.completed",
                    String.valueOf(reservation.getId()), event);
        } catch (Exception e) {
            log.warn("예매 완료 이벤트 발행 실패: reservationId={}", reservation.getId(), e);
        }

        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("결제 정보를 찾을 수 없습니다."));
        return PaymentResponse.from(payment);
    }
}
