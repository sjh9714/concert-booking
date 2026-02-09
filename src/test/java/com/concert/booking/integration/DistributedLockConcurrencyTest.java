package com.concert.booking.integration;

import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Seat;
import com.concert.booking.domain.SeatStatus;
import com.concert.booking.domain.User;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import com.concert.booking.service.queue.QueueService;
import com.concert.booking.service.reservation.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class DistributedLockConcurrencyTest {

    @Autowired
    @Qualifier("distributedLockReservationService")
    private ReservationService reservationService;

    @Autowired private QueueService queueService;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    private Long scheduleId;
    private Long targetSeatId;
    private List<Long> userIds;

    @BeforeEach
    void setUp() {
        // 콘서트 + 스케줄 + 좌석 1개 생성
        Concert concert = Concert.create("분산 락 테스트 콘서트", "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(30), LocalTime.of(20, 0), 1);
        concertScheduleRepository.save(schedule);
        scheduleId = schedule.getId();

        Seat seat = Seat.create(schedule, "VIP", 1, 1, 150000);
        seatRepository.save(seat);
        targetSeatId = seat.getId();

        // Redis 재고 초기화
        String stockKey = RedisKeyUtil.stockKey(scheduleId);
        redisTemplate.opsForValue().set(stockKey, "1");

        // 10명의 사용자 생성 + 대기열 토큰 발급
        userIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            User user = User.create(
                    "dist-lock-" + System.nanoTime() + "-" + i + "@test.com",
                    passwordEncoder.encode("password123"),
                    "분산락테스터" + i
            );
            userRepository.save(user);
            userIds.add(user.getId());

            // 대기열 진입 + 토큰 발급 (토큰 검증을 위해)
            queueService.enter(user.getId(), scheduleId);
            queueService.issueToken(user.getId(), scheduleId);
        }
    }

    @Test
    @DisplayName("분산 락: 10명이 동시에 같은 좌석 1개 예매 → 1명만 성공")
    void distributed_lock_concurrent_reservation_only_one_succeeds() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final Long userId = userIds.get(i);
            executor.submit(() -> {
                try {
                    ReservationRequest request = new ReservationRequest(scheduleId, List.of(targetSeatId));
                    reservationService.reserve(userId, request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 정확히 1명만 성공
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(9);

        // 좌석 상태: HELD
        Seat seat = seatRepository.findById(targetSeatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
    }
}
