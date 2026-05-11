package com.concert.booking.integration;

import com.concert.booking.common.exception.QueueNotReadyException;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.dto.queue.QueuePositionResponse;
import com.concert.booking.dto.queue.QueueTokenResponse;
import com.concert.booking.service.queue.QueueService;
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

@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class QueueServiceTest {

    @Autowired private QueueService queueService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    private Long scheduleId;

    @BeforeEach
    void setUp() {
        scheduleId = System.nanoTime();
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

        // 토큰 검증 성공
        boolean valid = queueService.validateToken(userId, scheduleId, tokenResponse.token());
        assertThat(valid).isTrue();
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
