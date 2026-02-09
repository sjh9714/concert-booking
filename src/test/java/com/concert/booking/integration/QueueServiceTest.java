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

    private static final Long SCHEDULE_ID = 999L;

    @BeforeEach
    void setUp() {
        // 테스트마다 대기열 초기화
        redisTemplate.delete("queue:schedule:" + SCHEDULE_ID);
    }

    @Test
    @DisplayName("대기열 진입 후 순위 조회 → 1번")
    void enter_and_get_position() {
        Long userId = 1L;

        QueuePositionResponse response = queueService.enter(userId, SCHEDULE_ID);

        assertThat(response.position()).isEqualTo(1);
        assertThat(response.totalWaiting()).isEqualTo(1);
    }

    @Test
    @DisplayName("중복 진입 방지 — 같은 userId로 2번 enter → 순위 동일")
    void duplicate_entry_prevention() {
        Long userId = 1L;

        queueService.enter(userId, SCHEDULE_ID);
        QueuePositionResponse second = queueService.enter(userId, SCHEDULE_ID);

        assertThat(second.position()).isEqualTo(1);
        assertThat(second.totalWaiting()).isEqualTo(1);
    }

    @Test
    @DisplayName("순위 ≤ threshold → 토큰 발급 + 검증 성공")
    void issue_and_validate_token() {
        Long userId = 1L;

        queueService.enter(userId, SCHEDULE_ID);
        QueueTokenResponse tokenResponse = queueService.issueToken(userId, SCHEDULE_ID);

        assertThat(tokenResponse.token()).isNotNull();
        assertThat(tokenResponse.scheduleId()).isEqualTo(SCHEDULE_ID);

        // 토큰 검증 성공
        boolean valid = queueService.validateToken(userId, SCHEDULE_ID, tokenResponse.token());
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("토큰 1회 사용 — consumeToken 후 validateToken → false")
    void token_single_use() {
        Long userId = 1L;

        queueService.enter(userId, SCHEDULE_ID);
        QueueTokenResponse tokenResponse = queueService.issueToken(userId, SCHEDULE_ID);

        // 토큰 소비
        queueService.consumeToken(userId, SCHEDULE_ID);

        // 소비 후 검증 실패
        boolean valid = queueService.validateToken(userId, SCHEDULE_ID, tokenResponse.token());
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("순위 > threshold → 토큰 발급 실패")
    void token_issue_fails_when_not_ready() {
        // 101명을 대기열에 추가
        for (long i = 1; i <= 101; i++) {
            queueService.enter(i, SCHEDULE_ID);
        }

        // 101번째 유저는 토큰 발급 불가
        assertThatThrownBy(() -> queueService.issueToken(101L, SCHEDULE_ID))
                .isInstanceOf(QueueNotReadyException.class);
    }
}
