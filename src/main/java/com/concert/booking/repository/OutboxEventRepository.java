package com.concert.booking.repository;

import com.concert.booking.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(nativeQuery = true, value = """
            SELECT *
            FROM outbox_events
            WHERE (
                    status = 'PENDING'
                    OR (
                        status = 'FAILED'
                        AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                    )
                  )
              AND (locked_at IS NULL OR locked_at < :staleBefore)
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEvent> findPublishableForUpdate(@Param("now") LocalDateTime now,
                                               @Param("staleBefore") LocalDateTime staleBefore,
                                               @Param("limit") int limit);
}
