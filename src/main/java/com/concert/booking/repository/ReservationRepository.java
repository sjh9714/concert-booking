package com.concert.booking.repository;

import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByUserId(Long userId);

    @Query("SELECT COUNT(r) FROM Reservation r WHERE r.schedule.id = :scheduleId")
    long countByScheduleId(@Param("scheduleId") Long scheduleId);

    @Query("""
            SELECT r.id
            FROM Reservation r
            WHERE r.status = :status
              AND r.expiresAt < :dateTime
            ORDER BY r.expiresAt ASC
            """)
    List<Long> findExpiredPendingIds(@Param("status") ReservationStatus status,
                                     @Param("dateTime") LocalDateTime dateTime,
                                     Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query("DELETE FROM Reservation r WHERE r.schedule.id = :scheduleId")
    void deleteByScheduleId(@Param("scheduleId") Long scheduleId);
}
