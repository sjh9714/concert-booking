package com.concert.booking.consumer;

import com.concert.booking.event.ReservationCancelledEvent;

public interface SeatReleaseFailurePolicy {

    void beforeRelease(ReservationCancelledEvent event);
}
