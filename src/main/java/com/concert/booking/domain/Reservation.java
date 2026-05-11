package com.concert.booking.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_key", nullable = false, unique = true)
    private UUID reservationKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private ConcertSchedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "total_amount", nullable = false)
    private Integer totalAmount;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReservationSeat> reservationSeats = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public static Reservation create(User user, ConcertSchedule schedule,
                                     int totalAmount, LocalDateTime expiresAt) {
        Reservation reservation = new Reservation();
        reservation.reservationKey = UUID.randomUUID();
        reservation.user = user;
        reservation.schedule = schedule;
        reservation.status = ReservationStatus.PENDING;
        reservation.totalAmount = totalAmount;
        reservation.expiresAt = expiresAt;
        return reservation;
    }

    // PENDING → CONFIRMED (결제 완료 시)
    public void confirm() {
        if (!canConfirm()) {
            throw new IllegalStateException("대기 중인 예매만 확정할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = ReservationStatus.CONFIRMED;
        this.expiresAt = null;
    }

    // PENDING → CANCELLED (사용자 취소)
    public void cancel() {
        if (!canCancel()) {
            throw new IllegalStateException("대기 중인 예매만 취소할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = ReservationStatus.CANCELLED;
    }

    // PENDING → EXPIRED (만료 처리)
    public void expire() {
        expire(LocalDateTime.now());
    }

    public void expire(LocalDateTime now) {
        if (this.status != ReservationStatus.PENDING) {
            throw new IllegalStateException("대기 중인 예매만 만료 처리할 수 있습니다. 현재 상태: " + this.status);
        }
        if (!canExpire(now)) {
            throw new IllegalStateException("expiresAt이 지나지 않은 예매는 만료 처리할 수 없습니다. expiresAt: " + this.expiresAt);
        }
        this.status = ReservationStatus.EXPIRED;
    }

    public boolean canConfirm() {
        return this.status == ReservationStatus.PENDING;
    }

    public boolean canCancel() {
        return this.status == ReservationStatus.PENDING;
    }

    public boolean canExpire(LocalDateTime now) {
        return this.status == ReservationStatus.PENDING
                && this.expiresAt != null
                && !now.isBefore(this.expiresAt);
    }

    public boolean isExpired() {
        return canExpire(LocalDateTime.now());
    }

    public void addReservationSeat(ReservationSeat reservationSeat) {
        this.reservationSeats.add(reservationSeat);
    }
}
