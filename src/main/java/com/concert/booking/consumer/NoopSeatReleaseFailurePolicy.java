package com.concert.booking.consumer;

import com.concert.booking.event.ReservationCancelledEvent;
import org.springframework.stereotype.Component;

@Component
public class NoopSeatReleaseFailurePolicy implements SeatReleaseFailurePolicy {

    @Override
    public void beforeRelease(ReservationCancelledEvent event) {
        // Default production policy does not inject failures.
    }
}
