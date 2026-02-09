package com.concert.booking.repository;

import com.concert.booking.domain.ReservationSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {

    List<ReservationSeat> findByReservationId(Long reservationId);

    @Modifying
    @Query(nativeQuery = true,
            value = "DELETE FROM reservation_seats WHERE reservation_id IN (SELECT id FROM reservations WHERE schedule_id = :scheduleId)")
    void deleteByScheduleId(@Param("scheduleId") Long scheduleId);
}
