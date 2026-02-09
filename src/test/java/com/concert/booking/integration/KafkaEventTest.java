package com.concert.booking.integration;

import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.*;
import com.concert.booking.dto.payment.PaymentRequest;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.repository.*;
import com.concert.booking.service.payment.PaymentService;
import com.concert.booking.service.reservation.ReservationService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class KafkaEventTest {

    @Autowired private ReservationService reservationService;
    @Autowired private PaymentService paymentService;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private Long userId;
    private Long scheduleId;
    private Long seatId;

    @BeforeEach
    void setUp() {
        User user = User.create(
                "kafka-test-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "카프카테스터"
        );
        userRepository.save(user);
        userId = user.getId();

        Concert concert = Concert.create("Kafka 테스트 콘서트", "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(30), LocalTime.of(20, 0), 10);
        concertScheduleRepository.save(schedule);
        scheduleId = schedule.getId();

        Seat seat = Seat.create(schedule, "A", 1, 1, 100000);
        seatRepository.save(seat);
        seatId = seat.getId();
    }

    @Test
    @DisplayName("결제 완료 시 reservation.completed 이벤트가 Kafka에 발행된다")
    void payment_publishes_completed_event() {
        // 예매 생성
        ReservationRequest reservationRequest = new ReservationRequest(scheduleId, List.of(seatId));
        var reservationResponse = reservationService.reserve(userId, reservationRequest);

        // Kafka Consumer 생성 (테스트용)
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-completed-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("reservation.completed"));

            // 파티션 할당 대기 (첫 poll로 rebalance 트리거)
            consumer.poll(Duration.ofMillis(2000));

            // 결제 실행
            PaymentRequest paymentRequest = new PaymentRequest(reservationResponse.id());
            paymentService.pay(userId, paymentRequest);

            // 이벤트 수신 확인 (최대 15초 대기)
            ConsumerRecords<String, String> records = ConsumerRecords.empty();
            long deadline = System.currentTimeMillis() + 15000;

            while (records.isEmpty() && System.currentTimeMillis() < deadline) {
                records = consumer.poll(Duration.ofMillis(500));
            }

            assertThat(records.count()).isGreaterThanOrEqualTo(1);

            var record = records.iterator().next();
            assertThat(record.topic()).isEqualTo("reservation.completed");
            assertThat(record.key()).isEqualTo(String.valueOf(reservationResponse.id()));
            assertThat(record.value()).contains("reservationId");
        }

        // 예매 상태 확인: CONFIRMED
        Reservation reservation = reservationRepository.findById(reservationResponse.id()).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }
}
