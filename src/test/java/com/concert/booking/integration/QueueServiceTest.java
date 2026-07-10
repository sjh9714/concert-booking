package com.concert.booking.integration;

import com.concert.booking.common.exception.QueueNotReadyException;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.dto.queue.QueuePositionResponse;
import com.concert.booking.dto.queue.QueueTokenResponse;
import com.concert.booking.dto.queue.QueueStatus;
import com.concert.booking.service.queue.QueueService;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class QueueServiceTest {

    @Autowired private QueueService queueService;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;

    private Long scheduleId;

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(Concert.create(
                "Queue service test " + System.nanoTime(), "설명", "장소", "아티스트"));
        ConcertSchedule schedule = concertScheduleRepository.save(ConcertSchedule.create(
                concert, LocalDate.now().plusDays(10), LocalTime.of(19, 0), 120));
        scheduleId = schedule.getId();
        // 테스트마다 대기열 초기화
        redisTemplate.delete("queue:schedule:" + scheduleId);
    }

    @Test
    @DisplayName("대기열 진입 후 순위 조회 → 1번")
    void enter_and_get_position() {
        Long userId = 1L;

        QueuePositionResponse response = queueService.enter(userId, scheduleId);

        assertThat(response.position()).isEqualTo(1);
        assertThat(response.totalWaiting()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(QueueStatus.READY);
    }

    @Test
    @DisplayName("중복 진입 방지 — 같은 userId로 2번 enter → 순위 동일")
    void duplicate_entry_prevention() {
        Long userId = 1L;

        queueService.enter(userId, scheduleId);
        QueuePositionResponse second = queueService.enter(userId, scheduleId);

        assertThat(second.position()).isEqualTo(1);
        assertThat(second.totalWaiting()).isEqualTo(1);
    }

    @Test
    @DisplayName("순위 ≤ threshold → 토큰 발급 + 검증 성공")
    void issue_and_validate_token() {
        Long userId = 1L;

        queueService.enter(userId, scheduleId);
        QueueTokenResponse tokenResponse = queueService.issueToken(userId, scheduleId);

        assertThat(tokenResponse.token()).isNotNull();
        assertThat(tokenResponse.scheduleId()).isEqualTo(scheduleId);
        assertThat(tokenResponse.expiresAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(4));
        assertThat(tokenResponse.expiresAt()).isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(6));

        // 토큰 검증 성공
        boolean valid = queueService.validateToken(userId, scheduleId, tokenResponse.token());
        assertThat(valid).isTrue();
        assertThat(queueService.getPosition(userId, scheduleId).status()).isEqualTo(QueueStatus.ADMITTED);
    }

    @Test
    @DisplayName("동일 사용자의 동시 토큰 발급은 하나의 토큰을 멱등 반환한다")
    void concurrent_issue_returns_one_token() throws InterruptedException {
        Long userId = 7L;
        queueService.enter(userId, scheduleId);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(12);
        Set<String> tokens = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        var executor = Executors.newFixedThreadPool(12);

        for (int i = 0; i < 12; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    tokens.add(queueService.issueToken(userId, scheduleId).token());
                } catch (Throwable e) {
                    errors.add(e);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(errors).isEmpty();
        assertThat(tokens).hasSize(1);
        assertThat(queueService.getPosition(userId, scheduleId).status()).isEqualTo(QueueStatus.ADMITTED);
    }

    @Test
    @DisplayName("토큰 발급과 동시 재진입이 겹쳐도 사용자를 대기열에 다시 삽입하지 않는다")
    void concurrent_reentry_cannot_reinsert_after_token_issue() throws InterruptedException {
        Long userId = 17L;

        for (int attempt = 0; attempt < 30; attempt++) {
            queueService.consumeToken(userId, scheduleId);
            queueService.removeFromQueue(userId, scheduleId);
            queueService.enter(userId, scheduleId);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            AtomicReference<String> issuedToken = new AtomicReference<>();
            var executor = Executors.newFixedThreadPool(2);

            executor.submit(() -> {
                try {
                    start.await();
                    queueService.enter(userId, scheduleId);
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    start.await();
                    issuedToken.set(queueService.issueToken(userId, scheduleId).token());
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });

            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(errors).isEmpty();
            assertThat(redisTemplate.opsForZSet().rank("queue:schedule:" + scheduleId, String.valueOf(userId)))
                    .isNull();
            assertThat(queueService.issueToken(userId, scheduleId).token()).isEqualTo(issuedToken.get());
        }
    }

    @Test
    @DisplayName("토큰 1회 사용 — consumeToken 후 validateToken → false")
    void token_single_use() {
        Long userId = 1L;

        queueService.enter(userId, scheduleId);
        QueueTokenResponse tokenResponse = queueService.issueToken(userId, scheduleId);

        // 토큰 소비
        queueService.consumeToken(userId, scheduleId);

        // 소비 후 검증 실패
        boolean valid = queueService.validateToken(userId, scheduleId, tokenResponse.token());
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("토큰 TTL 종료 marker가 남아 있으면 EXPIRED이고 명시적 재진입 시 새 순번을 받는다")
    void expired_token_is_distinct_from_never_joined() {
        Long userId = 23L;
        assertThat(queueService.getPosition(userId, scheduleId).status()).isEqualTo(QueueStatus.NOT_JOINED);

        queueService.enter(userId, scheduleId);
        queueService.issueToken(userId, scheduleId);
        redisTemplate.delete(RedisKeyUtil.tokenKey(userId, scheduleId));

        assertThat(redisTemplate.hasKey(RedisKeyUtil.admissionMarkerKey(userId, scheduleId))).isTrue();
        assertThat(queueService.getPosition(userId, scheduleId).status()).isEqualTo(QueueStatus.EXPIRED);

        QueuePositionResponse rejoined = queueService.enter(userId, scheduleId);
        assertThat(rejoined.status()).isEqualTo(QueueStatus.READY);
        assertThat(rejoined.position()).isEqualTo(1L);
        assertThat(redisTemplate.hasKey(RedisKeyUtil.admissionMarkerKey(userId, scheduleId))).isFalse();
    }

    @Test
    @DisplayName("순위 > threshold → 토큰 발급 실패")
    void token_issue_fails_when_not_ready() {
        // 101명을 대기열에 추가
        for (long i = 1; i <= 100; i++) {
            queueService.enter(i, scheduleId);
        }
        sleepUntilNextMillisecond();
        queueService.enter(101L, scheduleId);

        // 101번째 유저는 토큰 발급 불가
        assertThatThrownBy(() -> queueService.issueToken(101L, scheduleId))
                .isInstanceOf(QueueNotReadyException.class);
    }

    private void sleepUntilNextMillisecond() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
