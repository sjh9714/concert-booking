package com.concert.booking.domain;

import com.concert.booking.event.ReservationCancelledEvent;
import com.concert.booking.event.ReservationCompletedEvent;
import com.concert.booking.event.ReservationCreatedEvent;

public enum OutboxEventType {
    RESERVATION_CREATED("reservation.created", ReservationCreatedEvent.class),
    RESERVATION_CONFIRMED("reservation.completed", ReservationCompletedEvent.class),
    RESERVATION_CANCELLED("reservation.cancelled", ReservationCancelledEvent.class),
    RESERVATION_EXPIRED("reservation.cancelled", ReservationCancelledEvent.class);

    private final String topic;
    private final Class<?> payloadClass;

    OutboxEventType(String topic, Class<?> payloadClass) {
        this.topic = topic;
        this.payloadClass = payloadClass;
    }

    public String topic() {
        return topic;
    }

    public Class<?> payloadClass() {
        return payloadClass;
    }
}
