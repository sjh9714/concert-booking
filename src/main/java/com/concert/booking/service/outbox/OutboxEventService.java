package com.concert.booking.service.outbox;

import com.concert.booking.domain.OutboxEvent;
import com.concert.booking.domain.OutboxEventType;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationSeat;
import com.concert.booking.event.ReservationCancelledEvent;
import com.concert.booking.event.ReservationCompletedEvent;
import com.concert.booking.event.ReservationCreatedEvent;
import com.concert.booking.repository.OutboxEventRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private static final String RESERVATION_AGGREGATE = "Reservation";

    private final OutboxEventRepository outboxEventRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveReservationCreated(Reservation reservation) {
        List<Long> seatIds = seatIds(reservation.getId());
        ReservationCreatedEvent event = new ReservationCreatedEvent(
                reservation.getId(),
                reservation.getUser().getId(),
                reservation.getSchedule().getId(),
                seatIds,
                reservation.getTotalAmount(),
                reservation.getExpiresAt(),
                LocalDateTime.now()
        );
        save(OutboxEventType.RESERVATION_CREATED, reservation.getId(), event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveReservationConfirmed(Reservation reservation) {
        ReservationCompletedEvent event = new ReservationCompletedEvent(
                reservation.getId(),
                reservation.getUser().getId(),
                reservation.getSchedule().getId(),
                reservation.getTotalAmount(),
                LocalDateTime.now()
        );
        save(OutboxEventType.RESERVATION_CONFIRMED, reservation.getId(), event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveReservationCancelled(Reservation reservation) {
        saveReleaseEvent(OutboxEventType.RESERVATION_CANCELLED, reservation, "USER_CANCELLED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveReservationExpired(Reservation reservation) {
        saveReleaseEvent(OutboxEventType.RESERVATION_EXPIRED, reservation, "EXPIRED");
    }

    private void saveReleaseEvent(OutboxEventType eventType, Reservation reservation, String reason) {
        ReservationCancelledEvent event = new ReservationCancelledEvent(
                reservation.getId(),
                reservation.getUser().getId(),
                reservation.getSchedule().getId(),
                seatIds(reservation.getId()),
                reservation.getTotalAmount(),
                reason
        );
        save(eventType, reservation.getId(), event);
    }

    private void save(OutboxEventType eventType, Long aggregateId, Object payload) {
        outboxEventRepository.save(OutboxEvent.create(
                RESERVATION_AGGREGATE,
                aggregateId,
                eventType,
                eventType.topic(),
                serialize(payload)
        ));
    }

    private List<Long> seatIds(Long reservationId) {
        return reservationSeatRepository.findByReservationId(reservationId).stream()
                .map(ReservationSeat::getSeat)
                .map(seat -> seat.getId())
                .toList();
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload serialization failed.", e);
        }
    }
}
