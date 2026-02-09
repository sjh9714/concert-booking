package com.concert.booking.repository;

import com.concert.booking.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByReservationId(Long reservationId);

    @Modifying
    @Query(nativeQuery = true,
            value = "DELETE FROM payments WHERE reservation_id IN (SELECT id FROM reservations WHERE schedule_id = :scheduleId)")
    void deleteByScheduleId(@Param("scheduleId") Long scheduleId);
}
