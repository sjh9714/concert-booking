package com.concert.booking.repository;

import com.concert.booking.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByReservationId(Long reservationId);

    Optional<Payment> findByReservationIdAndIdempotencyKey(Long reservationId, String idempotencyKey);

    List<Payment> findByReservationIdIn(Collection<Long> reservationIds);

    @Query(nativeQuery = true,
            value = "SELECT COUNT(*) FROM payments WHERE reservation_id IN (SELECT id FROM reservations WHERE schedule_id = :scheduleId)")
    long countByScheduleId(@Param("scheduleId") Long scheduleId);

    @Modifying
    @Query(nativeQuery = true,
            value = "DELETE FROM payments WHERE reservation_id IN (SELECT id FROM reservations WHERE schedule_id = :scheduleId)")
    void deleteByScheduleId(@Param("scheduleId") Long scheduleId);
}
