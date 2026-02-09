package com.concert.booking.repository;

import com.concert.booking.domain.Seat;
import com.concert.booking.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScheduleId(Long scheduleId);

    List<Seat> findByScheduleIdAndStatus(Long scheduleId, SeatStatus status);

    // 비관적 락: 좌석 ID 목록으로 AVAILABLE 좌석 조회 + FOR UPDATE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id IN :seatIds AND s.status = 'AVAILABLE' ORDER BY s.id")
    List<Seat> findAllByIdInAndAvailableForUpdate(@Param("seatIds") List<Long> seatIds);

    // 낙관적 락: 락 없이 AVAILABLE 좌석 조회 (커밋 시 @Version으로 충돌 감지)
    @Query("SELECT s FROM Seat s WHERE s.id IN :seatIds AND s.status = 'AVAILABLE' ORDER BY s.id")
    List<Seat> findAllByIdInAndAvailable(@Param("seatIds") List<Long> seatIds);
}
