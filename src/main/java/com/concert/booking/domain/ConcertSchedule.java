package com.concert.booking.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "concert_schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConcertSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concert_id", nullable = false)
    private Concert concert;

    @Column(name = "schedule_date", nullable = false)
    private LocalDate scheduleDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @Column(name = "available_seats", nullable = false)
    private Integer availableSeats;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public static ConcertSchedule create(Concert concert, LocalDate scheduleDate,
                                          LocalTime startTime, int totalSeats) {
        ConcertSchedule schedule = new ConcertSchedule();
        schedule.concert = concert;
        schedule.scheduleDate = scheduleDate;
        schedule.startTime = startTime;
        schedule.totalSeats = totalSeats;
        schedule.availableSeats = totalSeats;
        return schedule;
    }

    // 잔여 좌석 감소
    public void decreaseAvailableSeats(int count) {
        if (this.availableSeats < count) {
            throw new IllegalStateException("잔여 좌석이 부족합니다.");
        }
        this.availableSeats -= count;
    }

    // 잔여 좌석 복원
    public void increaseAvailableSeats(int count) {
        this.availableSeats += count;
    }
}
