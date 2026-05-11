package com.concert.booking.service.reservation;

import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationStatus;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.service.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationExpirationScheduler {

    private final ReservationRepository reservationRepository;
    private final OutboxEventService outboxEventService;
    private final TransactionTemplate transactionTemplate;

    @Value("${reservation.expiration.batch-size:100}")
    private int batchSize;

    // 30초마다 실행, ShedLock으로 서버 2대 중복 실행 방지
    @Scheduled(fixedRate = 30000)
    @SchedulerLock(name = "expireReservations", lockAtLeastFor = "10s", lockAtMostFor = "30s")
    public void expireReservations() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> expiredReservationIds = reservationRepository.findExpiredPendingIds(
                ReservationStatus.PENDING,
                now,
                PageRequest.of(0, batchSize)
        );

        if (expiredReservationIds.isEmpty()) {
            return;
        }

        log.info("만료 예매 처리 시작: {}건", expiredReservationIds.size());

        int expiredCount = 0;
        for (Long reservationId : expiredReservationIds) {
            if (expireReservation(reservationId, now)) {
                expiredCount++;
            }
        }

        log.info("만료 예매 처리 완료: {}건", expiredCount);
    }

    public boolean expireReservation(Long reservationId, LocalDateTime now) {
        Boolean expired = transactionTemplate.execute(status ->
                expireReservationInTransaction(reservationId, now));
        return Boolean.TRUE.equals(expired);
    }

    private boolean expireReservationInTransaction(Long reservationId, LocalDateTime now) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElse(null);
        if (reservation == null || !reservation.canExpire(now)) {
            return false;
        }

        reservation.expire(now);
        outboxEventService.saveReservationExpired(reservation);
        return true;
    }
}
