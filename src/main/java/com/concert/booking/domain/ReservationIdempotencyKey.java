package com.concert.booking.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "reservation_idempotency_keys",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_idempotency_scope",
                columnNames = {"user_id", "schedule_id", "idempotency_key"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationIdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationIdempotencyStatus status;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static ReservationIdempotencyKey create(Long userId, Long scheduleId,
                                                   String idempotencyKey, String requestHash) {
        ReservationIdempotencyKey key = new ReservationIdempotencyKey();
        key.userId = userId;
        key.scheduleId = scheduleId;
        key.idempotencyKey = idempotencyKey;
        key.requestHash = requestHash;
        key.status = ReservationIdempotencyStatus.PROCESSING;
        return key;
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

    public void complete(Reservation reservation) {
        this.reservation = reservation;
        this.status = ReservationIdempotencyStatus.COMPLETED;
    }
}
