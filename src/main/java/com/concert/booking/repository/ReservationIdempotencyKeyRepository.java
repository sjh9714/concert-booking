package com.concert.booking.repository;

import com.concert.booking.domain.ReservationIdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReservationIdempotencyKeyRepository extends JpaRepository<ReservationIdempotencyKey, Long> {

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO reservation_idempotency_keys
                (user_id, schedule_id, idempotency_key, request_hash, status, created_at, updated_at)
            VALUES
                (:userId, :scheduleId, :idempotencyKey, :requestHash, 'PROCESSING', NOW(), NOW())
            ON CONFLICT (user_id, schedule_id, idempotency_key) DO NOTHING
            """)
    int insertClaim(@Param("userId") Long userId,
                    @Param("scheduleId") Long scheduleId,
                    @Param("idempotencyKey") String idempotencyKey,
                    @Param("requestHash") String requestHash);

    @Query("""
            SELECT k
            FROM ReservationIdempotencyKey k
            LEFT JOIN FETCH k.reservation
            WHERE k.userId = :userId
              AND k.scheduleId = :scheduleId
              AND k.idempotencyKey = :idempotencyKey
            """)
    Optional<ReservationIdempotencyKey> findByScope(@Param("userId") Long userId,
                                                    @Param("scheduleId") Long scheduleId,
                                                    @Param("idempotencyKey") String idempotencyKey);

    @Modifying
    @Query(nativeQuery = true,
            value = "DELETE FROM reservation_idempotency_keys WHERE id = :id AND status = 'PROCESSING'")
    int deleteProcessingById(@Param("id") Long id);

    @Modifying
    @Query(nativeQuery = true,
            value = "DELETE FROM reservation_idempotency_keys WHERE schedule_id = :scheduleId")
    void deleteByScheduleId(@Param("scheduleId") Long scheduleId);
}
