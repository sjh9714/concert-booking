package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.BadRequestException;
import com.concert.booking.common.exception.ConflictException;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.ReservationIdempotencyKey;
import com.concert.booking.domain.ReservationIdempotencyStatus;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.repository.ReservationIdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReservationIdempotencyService {

    private static final int MAX_SEAT_COUNT = 4;
    private static final int MAX_KEY_LENGTH = 120;
    private static final Duration PROCESSING_WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final long PROCESSING_POLL_INTERVAL_MS = 50;

    private final ReservationIdempotencyKeyRepository repository;
    private final PlatformTransactionManager transactionManager;

    public ReservationClaim claimOrReplay(Long userId, Long scheduleId, String idempotencyKey, List<Long> seatIds) {
        validateIdempotencyKey(idempotencyKey);
        validateSeatIds(seatIds);
        String requestHash = requestHash(seatIds);
        Instant deadline = Instant.now().plus(PROCESSING_WAIT_TIMEOUT);

        while (true) {
            ReservationClaim claim = inRequiresNew(() -> tryClaimOrReplay(userId, scheduleId, idempotencyKey, requestHash));
            if (!claim.processing()) {
                return claim;
            }

            if (Instant.now().isAfter(deadline)) {
                throw new ConflictException("동일한 멱등성 키의 예매 요청이 아직 처리 중입니다. 잠시 후 다시 시도해주세요.");
            }
            sleepQuietly();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Long claimId, Reservation reservation) {
        ReservationIdempotencyKey key = repository.findById(claimId)
                .orElseThrow(() -> new IllegalStateException("예매 멱등성 키를 찾을 수 없습니다."));
        key.complete(reservation);
    }

    public void deleteProcessingClaim(Long claimId) {
        inRequiresNew(() -> {
            repository.deleteProcessingById(claimId);
            return null;
        });
    }

    private ReservationClaim tryClaimOrReplay(Long userId, Long scheduleId, String idempotencyKey, String requestHash) {
        int inserted = repository.insertClaim(userId, scheduleId, idempotencyKey, requestHash);
        ReservationIdempotencyKey key = repository.findByScope(userId, scheduleId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("예매 멱등성 키 저장에 실패했습니다."));

        if (!requestHash.equals(key.getRequestHash())) {
            throw new ConflictException("같은 Idempotency-Key로 다른 좌석 예매 요청을 보낼 수 없습니다.");
        }

        if (inserted == 1) {
            return ReservationClaim.owner(key.getId(), requestHash);
        }

        if (key.getStatus() == ReservationIdempotencyStatus.COMPLETED && key.getReservation() != null) {
            return ReservationClaim.replay(ReservationResponse.from(key.getReservation()));
        }

        return ReservationClaim.inProgress();
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BadRequestException("Idempotency-Key header는 필수입니다.");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new BadRequestException("Idempotency-Key는 최대 120자까지 허용됩니다.");
        }
    }

    private void validateSeatIds(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new BadRequestException("좌석은 1개 이상 선택해야 합니다.");
        }
        if (seatIds.size() > MAX_SEAT_COUNT) {
            throw new BadRequestException("최대 4석까지 예매 가능합니다.");
        }
        if (seatIds.stream().anyMatch(id -> id == null)) {
            throw new BadRequestException("좌석 ID는 null일 수 없습니다.");
        }

        Set<Long> uniqueSeatIds = new HashSet<>(seatIds);
        if (uniqueSeatIds.size() != seatIds.size()) {
            throw new BadRequestException("중복된 좌석 ID는 허용되지 않습니다.");
        }
    }

    private String requestHash(List<Long> seatIds) {
        String canonicalSeatIds = seatIds.stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalSeatIds.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 해시를 사용할 수 없습니다.", e);
        }
    }

    private <T> T inRequiresNew(Supplier<T> supplier) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> supplier.get());
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(PROCESSING_POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConflictException("예매 멱등성 처리 대기 중 인터럽트가 발생했습니다.");
        }
    }

    public record ReservationClaim(Long claimId, String requestHash,
                                   ReservationResponse replayResponse, boolean processing) {

        static ReservationClaim owner(Long claimId, String requestHash) {
            return new ReservationClaim(claimId, requestHash, null, false);
        }

        static ReservationClaim replay(ReservationResponse response) {
            return new ReservationClaim(null, null, response, false);
        }

        static ReservationClaim inProgress() {
            return new ReservationClaim(null, null, null, true);
        }

        public boolean replay() {
            return replayResponse != null;
        }
    }
}
