package com.concert.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    private static final int LAST_ERROR_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 80)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 80)
    private OutboxEventType eventType;

    @Column(nullable = false, length = 255)
    private String topic;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxEventStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "dead_at")
    private LocalDateTime deadAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    public static OutboxEvent create(String aggregateType, Long aggregateId,
                                     OutboxEventType eventType, String topic, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.topic = topic;
        event.payload = payload;
        event.status = OutboxEventStatus.PENDING;
        event.retryCount = 0;
        return event;
    }

    public void claim(LocalDateTime now) {
        this.lockedAt = now;
    }

    public void markPublished(LocalDateTime now) {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = now;
        this.lockedAt = null;
        this.lastError = null;
        this.nextAttemptAt = null;
        this.deadAt = null;
    }

    public void markFailed(String error, LocalDateTime now, LocalDateTime nextAttemptAt, int maxRetryCount) {
        this.retryCount += 1;
        this.lastError = truncate(error);
        this.lockedAt = null;
        this.updatedAt = now;

        if (this.retryCount >= maxRetryCount) {
            this.status = OutboxEventStatus.DEAD;
            this.nextAttemptAt = null;
            this.deadAt = now;
            return;
        }

        this.status = OutboxEventStatus.FAILED;
        this.nextAttemptAt = nextAttemptAt;
        this.deadAt = null;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= LAST_ERROR_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
