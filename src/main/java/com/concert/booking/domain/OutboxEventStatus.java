package com.concert.booking.domain;

public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
