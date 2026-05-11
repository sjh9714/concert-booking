package com.concert.booking.integration;

import com.concert.booking.common.util.RedisKeyUtil;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.consumer.SeatReleaseFailurePolicy;
import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Seat;
import com.concert.booking.domain.SeatStatus;
import com.concert.booking.domain.User;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.event.ReservationCancelledEvent;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.ReservationRepository;
import com.concert.booking.repository.ReservationSeatRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import com.concert.booking.service.kafka.DltReplayService;
import com.concert.booking.service.queue.QueueService;
import com.concert.booking.service.reservation.ReservationService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "kafka.consumer.seat-release-group=seat-release-dlt-replay",
        "spring.kafka.listener.auto-startup=true"
})
@ActiveProfiles("test")
@Import({TestContainersConfig.class, KafkaDltReplayIntegrationTest.FailurePolicyConfig.class})
class KafkaDltReplayIntegrationTest {

    @Autowired private ReservationService reservationService;
    @Autowired private QueueService queueService;
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private DltReplayService dltReplayService;
    @Autowired private TestSeatReleaseFailurePolicy failurePolicy;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationSeatRepository reservationSeatRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    @DisplayName("consumer 실패 메시지는 DLT로 이동하고 replay 후 좌석 반환이 멱등적으로 성공한다")
    void failed_cancelled_event_moves_to_dlt_and_replay_releases_seat_once() throws Exception {
        Scenario failedScenario = createPendingScenario();
        ReservationCancelledEvent failedEvent = event(failedScenario.reservationId(), failedScenario, "FORCE_DLT");

        failurePolicy.failForceDlt.set(true);
        kafkaTemplate.send("reservation.cancelled", String.valueOf(failedScenario.reservationId()), failedEvent).get(5, TimeUnit.SECONDS);

        ConsumerRecord<String, String> dltRecord = awaitDltRecord("reservation.cancelled.DLT");
        assertThat(dltRecord.key()).isEqualTo(String.valueOf(failedScenario.reservationId()));
        assertDltHeaders(dltRecord);

        failurePolicy.failForceDlt.set(false);
        assertThat(dltReplayService.replay("reservation.cancelled.DLT", 10).replayedCount()).isGreaterThanOrEqualTo(1);

        awaitReleased(failedScenario);
        assertThat(concertScheduleRepository.findById(failedScenario.scheduleId()).orElseThrow().getAvailableSeats()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(failedScenario.scheduleId()))).isEqualTo("1");

        dltReplayService.replay("reservation.cancelled.DLT", 10);

        awaitReleased(failedScenario);
        assertThat(concertScheduleRepository.findById(failedScenario.scheduleId()).orElseThrow().getAvailableSeats()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeyUtil.stockKey(failedScenario.scheduleId()))).isEqualTo("1");

        Scenario normalScenario = createPendingScenario();
        ReservationCancelledEvent normalEvent = event(normalScenario.reservationId(), normalScenario, "USER_CANCELLED");
        kafkaTemplate.send("reservation.cancelled", String.valueOf(normalScenario.reservationId()), normalEvent).get(5, TimeUnit.SECONDS);

        awaitReleased(normalScenario);
        assertThat(concertScheduleRepository.findById(normalScenario.scheduleId()).orElseThrow().getAvailableSeats()).isEqualTo(1);
    }

    private ConsumerRecord<String, String> awaitDltRecord(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-assert-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            AtomicReferenceRecord holder = new AtomicReferenceRecord();

            Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        consumer.poll(Duration.ofMillis(500)).forEach(holder::set);
                        assertThat(holder.record).isNotNull();
                    });
            return holder.record;
        }
    }

    private void assertDltHeaders(ConsumerRecord<String, String> record) {
        assertThat(header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isNotNull();
        assertThat(header(record, KafkaHeaders.DLT_ORIGINAL_PARTITION)).isNotNull();
        assertThat(header(record, KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();
        assertThat(header(record, KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotNull();
    }

    private Header header(ConsumerRecord<String, String> record, String name) {
        return record.headers().lastHeader(name);
    }

    private Scenario createPendingScenario() {
        User user = User.create(
                "dlt-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "DLT테스터"
        );
        userRepository.save(user);

        Concert concert = Concert.create("DLT 테스트 콘서트", "설명", "장소", "아티스트");
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

        queueService.enter(user.getId(), schedule.getId());
        String queueToken = queueService.issueToken(user.getId(), schedule.getId()).token();
        Long reservationId = reservationService.reserve(
                user.getId(),
                new ReservationRequest(schedule.getId(), List.of(seat.getId()), queueToken),
                "dlt-reservation-" + UUID.randomUUID()).id();

        redisTemplate.opsForValue().set(RedisKeyUtil.stockKey(schedule.getId()), "0");
        redisTemplate.opsForValue().set(RedisKeyUtil.seatHoldKey(seat.getId()), String.valueOf(reservationId));

        return new Scenario(user.getId(), schedule.getId(), seat.getId(), reservationId);
    }

    private ReservationCancelledEvent event(Long reservationId, Scenario scenario, String reason) {
        return new ReservationCancelledEvent(
                reservationId,
                scenario.userId(),
                scenario.scheduleId(),
                reservationSeatRepository.findByReservationId(reservationId).stream()
                        .map(rs -> rs.getSeat().getId())
                        .toList(),
                reservationRepository.findById(reservationId).orElseThrow().getTotalAmount(),
                reason
        );
    }

    private void awaitReleased(Scenario scenario) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        assertThat(seatRepository.findById(scenario.seatId()).orElseThrow().getStatus())
                                .isEqualTo(SeatStatus.AVAILABLE));
    }

    private static class AtomicReferenceRecord {
        private ConsumerRecord<String, String> record;

        private void set(ConsumerRecord<String, String> record) {
            this.record = record;
        }
    }

    @TestConfiguration
    static class FailurePolicyConfig {
        @Bean
        @Primary
        TestSeatReleaseFailurePolicy testSeatReleaseFailurePolicy() {
            return new TestSeatReleaseFailurePolicy();
        }
    }

    static class TestSeatReleaseFailurePolicy implements SeatReleaseFailurePolicy {
        private final AtomicBoolean failForceDlt = new AtomicBoolean();

        @Override
        public void beforeRelease(ReservationCancelledEvent event) {
            if (failForceDlt.get() && "FORCE_DLT".equals(event.reason())) {
                throw new IllegalStateException("forced DLT failure");
            }
        }
    }

    private record Scenario(Long userId, Long scheduleId, Long seatId, Long reservationId) {
    }
}
