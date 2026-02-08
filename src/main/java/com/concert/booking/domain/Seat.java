package com.concert.booking.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private ConcertSchedule schedule;

    @Column(nullable = false, length = 10)
    private String section;

    @Column(name = "row_number", nullable = false)
    private Integer rowNumber;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Column(nullable = false)
    private Integer price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public static Seat create(ConcertSchedule schedule, String section,
                              int rowNumber, int seatNumber, int price) {
        Seat seat = new Seat();
        seat.schedule = schedule;
        seat.section = section;
        seat.rowNumber = rowNumber;
        seat.seatNumber = seatNumber;
        seat.price = price;
        seat.status = SeatStatus.AVAILABLE;
        return seat;
    }

    // AVAILABLE → HELD
    public void hold() {
        if (this.status != SeatStatus.AVAILABLE) {
            throw new IllegalStateException("예매 가능한 좌석이 아닙니다. 현재 상태: " + this.status);
        }
        this.status = SeatStatus.HELD;
    }

    // HELD → RESERVED
    public void reserve() {
        if (this.status != SeatStatus.HELD) {
            throw new IllegalStateException("홀드 상태의 좌석만 확정할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = SeatStatus.RESERVED;
    }

    // HELD → AVAILABLE (만료/취소 시 좌석 반환)
    public void release() {
        if (this.status != SeatStatus.HELD) {
            throw new IllegalStateException("홀드 상태의 좌석만 반환할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = SeatStatus.AVAILABLE;
    }
}
