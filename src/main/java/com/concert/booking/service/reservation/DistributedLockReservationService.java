package com.concert.booking.service.reservation;

import com.concert.booking.common.exception.InvalidReservationStateException;
import com.concert.booking.common.exception.ReservationNotFoundException;
import com.concert.booking.common.exception.SeatNotAvailableException;
import com.concert.booking.common.exception.SoldOutException;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.domain.*;
import com.concert.booking.dto.concert.SeatResponse;
import com.concert.booking.dto.reservation.ReservationDetailResponse;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.dto.reservation.ReservationResponse;
import com.concert.booking.repository.*;
import com.concert.booking.service.queue.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockReservationService implements ReservationService {

    private static final int HOLD_MINUTES = 5;
    private static final int SEAT_HOLD_TTL_SECONDS = 300; // 5분
    private static final long LOCK_WAIT_TIME = 3; // 3초 대기
    private static final long LOCK_LEASE_TIME = 5; // 5초 자동 해제

    private final UserRepository userRepository;
    private final ConcertScheduleRepository concertScheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final RedissonClient redissonClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final QueueService queueService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public ReservationResponse reserve(Long userId, ReservationRequest request) {
        // 1단계: Redis 재고 선검증 (atomic DECR)
        String stockKey = RedisKeyUtil.stockKey(request.scheduleId());
        int seatCount = request.seatIds().size();

        Long remaining = redisTemplate.opsForValue().decrement(stockKey, seatCount);
        if (remaining == null || remaining < 0) {
            // 복원
            redisTemplate.opsForValue().increment(stockKey, seatCount);
            throw new SoldOutException("잔여 좌석이 부족합니다.");
        }

        // 2단계: 좌석별 분산 락 (Redisson MultiLock)
        List<Long> sortedSeatIds = request.seatIds().stream().sorted().toList();

        List<RLock> locks = sortedSeatIds.stream()
                .map(id -> redissonClient.getLock("lock:seat:" + id))
                .toList();

        RLock multiLock = redissonClient.getMultiLock(locks.toArray(new RLock[0]));

        try {
            if (!multiLock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS)) {
                // 락 획득 실패 → 재고 복원
                redisTemplate.opsForValue().increment(stockKey, seatCount);
                throw new SeatNotAvailableException("좌석 잠금 획득에 실패했습니다. 다시 시도해주세요.");
            }

            try {
                // 3단계: DB 트랜잭션 (락 내부에서 실행)
                ReservationResponse response = transactionTemplate.execute(status -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

                    ConcertSchedule schedule = concertScheduleRepository.findByIdForUpdate(request.scheduleId())
                            .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다."));

                    // 락 없는 일반 SELECT + All-or-Nothing 검증
                    List<Seat> seats = seatRepository.findAllByIdInAndAvailable(sortedSeatIds);
                    if (seats.size() != sortedSeatIds.size()) {
                        throw new SeatNotAvailableException("선택한 좌석 중 이미 예매된 좌석이 있습니다.");
                    }

                    // 좌석 HOLD 처리
                    seats.forEach(Seat::hold);

                    // 총 금액 계산
                    int totalAmount = seats.stream().mapToInt(Seat::getPrice).sum();

                    // 예매 생성
                    LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(HOLD_MINUTES);
                    Reservation reservation = Reservation.create(user, schedule, totalAmount, expiresAt);
                    reservationRepository.save(reservation);

                    // 예매-좌석 매핑
                    for (Seat seat : seats) {
                        ReservationSeat rs = ReservationSeat.create(reservation, seat);
                        reservationSeatRepository.save(rs);
                        reservation.addReservationSeat(rs);
                    }

                    // 잔여 좌석 감소
                    schedule.decreaseAvailableSeats(seats.size());

                    // Redis 좌석 임시 점유 (TTL 5분)
                    for (Seat seat : seats) {
                        redisTemplate.opsForValue().set(
                                RedisKeyUtil.seatHoldKey(seat.getId()),
                                String.valueOf(reservation.getId()),
                                SEAT_HOLD_TTL_SECONDS, TimeUnit.SECONDS
                        );
                    }

                    return ReservationResponse.from(reservation);
                });

                // 토큰 소비 (1회 사용)
                queueService.consumeToken(userId, request.scheduleId());

                return response;

            } catch (Exception e) {
                // DB 트랜잭션 실패 → 재고 복원
                redisTemplate.opsForValue().increment(stockKey, seatCount);
                throw e;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            redisTemplate.opsForValue().increment(stockKey, seatCount);
            throw new SeatNotAvailableException("좌석 예매 중 인터럽트가 발생했습니다.");
        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
            }
        }
    }

    @Override
    public ReservationDetailResponse getReservation(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("예매를 찾을 수 없습니다."));

        List<SeatResponse> seats = reservation.getReservationSeats().stream()
                .map(rs -> SeatResponse.from(rs.getSeat()))
                .toList();

        return ReservationDetailResponse.from(reservation, seats);
    }

    @Override
    public List<ReservationResponse> getMyReservations(Long userId) {
        return reservationRepository.findByUserId(userId).stream()
                .map(ReservationResponse::from)
                .toList();
    }

    @Override
    public void cancelReservation(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("예매를 찾을 수 없습니다."));

        if (!reservation.getUser().getId().equals(userId)) {
            throw new InvalidReservationStateException("본인의 예매만 취소할 수 있습니다.");
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new InvalidReservationStateException("대기 중인 예매만 취소할 수 있습니다.");
        }

        // 예매 취소
        reservation.cancel();

        // 좌석 반환
        List<ReservationSeat> reservationSeats = reservationSeatRepository.findByReservationId(reservationId);
        for (ReservationSeat rs : reservationSeats) {
            rs.getSeat().release();
            // Redis 좌석 홀드 삭제
            redisTemplate.delete(RedisKeyUtil.seatHoldKey(rs.getSeat().getId()));
        }

        // 잔여 좌석 복원
        reservation.getSchedule().increaseAvailableSeats(reservationSeats.size());

        // Redis 재고 복원
        String stockKey = RedisKeyUtil.stockKey(reservation.getSchedule().getId());
        redisTemplate.opsForValue().increment(stockKey, reservationSeats.size());
    }
}
