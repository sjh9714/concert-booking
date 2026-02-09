package com.concert.booking.repository;

import com.concert.booking.domain.ConcertSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConcertScheduleRepository extends JpaRepository<ConcertSchedule, Long> {

    List<ConcertSchedule> findByConcertId(Long concertId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cs FROM ConcertSchedule cs WHERE cs.id = :id")
    Optional<ConcertSchedule> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query("UPDATE ConcertSchedule cs SET cs.availableSeats = cs.totalSeats WHERE cs.id = :id")
    void resetAvailableSeats(@Param("id") Long scheduleId);
}
