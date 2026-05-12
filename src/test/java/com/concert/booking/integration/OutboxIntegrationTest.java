package com.concert.booking.integration;

import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.OutboxEvent;
import com.concert.booking.domain.OutboxEventStatus;
import com.concert.booking.domain.OutboxEventType;
import com.concert.booking.domain.Reservation;
import com.concert.booking.domain.Seat;
import com.concert.booking.domain.User;
import com.concert.booking.dto.payment.PaymentRequest;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.OutboxEventRepository;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import com.concert.booking.service.outbox.OutboxRelayService;
import com.concert.booking.service.payment.PaymentService;
import com.concert.booking.service.queue.QueueService;
import com.concert.booking.service.reservation.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "outbox.relay.max-retry-count=2",
        "outbox.relay.initial-backoff-ms=0",
        "outbox.relay.max-backoff-ms=0"
})
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class OutboxIntegrationTest {

    @Autowired private ReservationService reservationService;
    @Autowired private PaymentService paymentService;
    @Autowired private QueueService queueService;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxRelayService outboxRelayService;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        Mockito.reset(kafkaTemplate);
    }

    @Test
    @DisplayName("결제 트랜잭션 성공 시 RESERVATION_CONFIRMED outbox event가 저장된다")
    void payment_success_stores_confirmed_outbox_event() {
        Scenario scenario = createScenario();
        Long reservationId = createPendingReservation(scenario, LocalDateTime.now().plusMinutes(5));

        paymentService.pay(scenario.userId(), new PaymentRequest(reservationId), key("payment"));

        assertThat(outboxEventRepository.findAll())
                .anySatisfy(event -> {
                    assertThat(event.getAggregateId()).isEqualTo(reservationId);
                    assertThat(event.getEventType()).isEqualTo(OutboxEventType.RESERVATION_CONFIRMED);
                    assertThat(event.getTopic()).isEqualTo("reservation.completed");
                    assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
                    assertThat(event.getPayload()).contains("\"reservationId\":" + reservationId);
                });
    }

    @Test
    @DisplayName("결제 rollback 시 RESERVATION_CONFIRMED outbox event는 저장되지 않는다")
    void payment_rollback_does_not_store_confirmed_outbox_event() {
        Scenario scenario = createScenario();
        Long reservationId = createPendingReservation(scenario, LocalDateTime.now().minusSeconds(1));
        long beforeCount = confirmedEventCount(reservationId);

        assertThatThrownBy(() -> paymentService.pay(
                scenario.userId(),
                new PaymentRequest(reservationId),
                key("payment-expired")))
                .isInstanceOf(RuntimeException.class);

        assertThat(confirmedEventCount(reservationId)).isEqualTo(beforeCount);
    }

    @Test
    @DisplayName("Kafka publish 실패 시 outbox event는 FAILED로 남고 다음 retry 성공 시 PUBLISHED가 된다")
    void relay_marks_failed_then_published_after_retry() {
        OutboxEvent event = outboxEventRepository.save(OutboxEvent.create(
                "Reservation",
                999L,
                OutboxEventType.RESERVATION_CONFIRMED,
                "reservation.completed",
                """
                        {"reservationId":999,"userId":1,"scheduleId":1,"totalAmount":1000,"confirmedAt":"2026-05-10T12:00:00"}
                        """
        ));

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

        assertThat(outboxRelayService.publishPendingBatch(10)).isZero();

        OutboxEvent failed = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("kafka down");
        assertThat(failed.getNextAttemptAt()).isNotNull();
        assertThat(failed.getDeadAt()).isNull();

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(completedSend());

        assertThat(outboxRelayService.publishPendingBatch(10)).isEqualTo(1);

        OutboxEvent published = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getLastError()).isNull();
        assertThat(published.getNextAttemptAt()).isNull();
        assertThat(published.getLockedAt()).isNull();
    }

    @Test
    @DisplayName("FAILED event는 nextAttemptAt 이전에는 relay 대상이 아니다")
    void relay_skips_failed_event_before_next_attempt_at() {
        OutboxEvent event = outboxEventRepository.save(outboxEvent());
        event.markFailed(
                "previous failure",
                LocalDateTime.now().minusSeconds(1),
                LocalDateTime.now().plusMinutes(10),
                5
        );
        outboxEventRepository.saveAndFlush(event);

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(completedSend());

        assertThat(outboxRelayService.publishPendingBatch(10)).isZero();

        OutboxEvent skipped = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(skipped.getRetryCount()).isEqualTo(1);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("FAILED event는 nextAttemptAt이 지났으면 다시 relay된다")
    void relay_retries_failed_event_after_next_attempt_at() {
        OutboxEvent event = outboxEventRepository.save(outboxEvent());
        event.markFailed(
                "previous failure",
                LocalDateTime.now().minusMinutes(2),
                LocalDateTime.now().minusSeconds(1),
                5
        );
        outboxEventRepository.saveAndFlush(event);

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(completedSend());

        assertThat(outboxRelayService.publishPendingBatch(10)).isEqualTo(1);

        OutboxEvent published = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("max retry count에 도달한 outbox event는 DEAD가 된다")
    void relay_marks_event_dead_after_max_retry_count() {
        OutboxEvent event = outboxEventRepository.save(outboxEvent());

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

        assertThat(outboxRelayService.publishPendingBatch(10)).isZero();
        assertThat(outboxRelayService.publishPendingBatch(10)).isZero();

        OutboxEvent dead = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(OutboxEventStatus.DEAD);
        assertThat(dead.getRetryCount()).isEqualTo(2);
        assertThat(dead.getDeadAt()).isNotNull();
        assertThat(dead.getNextAttemptAt()).isNull();
        assertThat(dead.getLockedAt()).isNull();
        assertThat(dead.getLastError()).contains("kafka down");
    }

    @Test
    @DisplayName("PUBLISHED event는 stale lock이 있어도 다시 relay되지 않는다")
    void relay_does_not_retry_published_event() {
        OutboxEvent event = outboxEventRepository.save(outboxEvent());
        event.markPublished(LocalDateTime.now().minusMinutes(5));
        event.claim(LocalDateTime.now().minusMinutes(2));
        outboxEventRepository.saveAndFlush(event);

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(completedSend());

        assertThat(outboxRelayService.publishPendingBatch(10)).isZero();

        OutboxEvent published = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("stale locked PENDING event는 다시 claim되어 relay된다")
    void relay_reclaims_stale_locked_pending_event() {
        OutboxEvent event = outboxEventRepository.save(outboxEvent());
        event.claim(LocalDateTime.now().minusMinutes(2));
        outboxEventRepository.saveAndFlush(event);

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(completedSend());

        assertThat(outboxRelayService.publishPendingBatch(10)).isEqualTo(1);

        OutboxEvent published = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    @DisplayName("DEAD event는 stale lock이 있어도 relay되지 않는다")
    void relay_does_not_retry_dead_event() {
        OutboxEvent event = outboxEventRepository.save(outboxEvent());
        event.markFailed("first failure", LocalDateTime.now().minusMinutes(3), LocalDateTime.now().minusMinutes(2), 2);
        event.markFailed("second failure", LocalDateTime.now().minusMinutes(2), LocalDateTime.now().minusMinutes(1), 2);
        event.claim(LocalDateTime.now().minusMinutes(2));
        outboxEventRepository.saveAndFlush(event);

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(completedSend());

        assertThat(outboxRelayService.publishPendingBatch(10)).isZero();

        OutboxEvent dead = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(OutboxEventStatus.DEAD);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    private long confirmedEventCount(Long reservationId) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> event.getAggregateId().equals(reservationId))
                .filter(event -> event.getEventType() == OutboxEventType.RESERVATION_CONFIRMED)
                .count();
    }

    private Long createPendingReservation(Scenario scenario, LocalDateTime expiresAt) {
        queueService.enter(scenario.userId(), scenario.scheduleId());
        String queueToken = queueService.issueToken(scenario.userId(), scenario.scheduleId()).token();
        Long reservationId = reservationService.reserve(
                scenario.userId(),
                new ReservationRequest(scenario.scheduleId(), List.of(scenario.seatId()), queueToken),
                key("reservation")).id();

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        ReflectionTestUtils.setField(reservation, "expiresAt", expiresAt);
        reservationRepository.saveAndFlush(reservation);
        return reservationId;
    }

    private Scenario createScenario() {
        User user = User.create(
                "outbox-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "아웃박스테스터"
        );
        userRepository.save(user);

        Concert concert = Concert.create("Outbox 테스트 콘서트", "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(
                concert,
                LocalDate.now().plusDays(30),
                LocalTime.of(20, 0),
                1
        );
        concertScheduleRepository.save(schedule);

        Seat seat = Seat.create(schedule, "A", 1, 1, 100000);
        seatRepository.save(seat);

        return new Scenario(user.getId(), schedule.getId(), seat.getId());
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, Object>> completedSend() {
        return CompletableFuture.completedFuture(Mockito.mock(SendResult.class));
    }

    private OutboxEvent outboxEvent() {
        return OutboxEvent.create(
                "Reservation",
                999L,
                OutboxEventType.RESERVATION_CONFIRMED,
                "reservation.completed",
                """
                        {"reservationId":999,"userId":1,"scheduleId":1,"totalAmount":1000,"confirmedAt":"2026-05-10T12:00:00"}
                        """
        );
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private record Scenario(Long userId, Long scheduleId, Long seatId) {
    }
}
