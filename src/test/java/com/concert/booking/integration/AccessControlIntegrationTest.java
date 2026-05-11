package com.concert.booking.integration;

import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.*;
import com.concert.booking.dto.auth.LoginRequest;
import com.concert.booking.dto.auth.SignupRequest;
import com.concert.booking.dto.payment.PaymentRequest;
import com.concert.booking.dto.queue.QueueEnterRequest;
import com.concert.booking.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class AccessControlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;

    private String ownerToken;
    private String otherToken;
    private Long scheduleId;
    private Long seatId1;
    private Long seatId2;

    @BeforeEach
    void setUp() throws Exception {
        Concert concert = Concert.create("접근 제어 테스트 콘서트", "설명", "장소", "아티스트");
        concertRepository.save(concert);

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(14), LocalTime.of(20, 0), 10);
        concertScheduleRepository.save(schedule);
        scheduleId = schedule.getId();

        Seat seat1 = Seat.create(schedule, "A", 1, 1, 100000);
        Seat seat2 = Seat.create(schedule, "A", 1, 2, 100000);
        seatRepository.saveAll(List.of(seat1, seat2));
        seatId1 = seat1.getId();
        seatId2 = seat2.getId();

        ownerToken = signupAndLogin("owner-" + System.nanoTime() + "@test.com");
        otherToken = signupAndLogin("other-" + System.nanoTime() + "@test.com");
    }

    @Test
    @DisplayName("사용자는 본인의 reservation을 조회할 수 있다")
    void owner_can_get_own_reservation() throws Exception {
        Long reservationId = createReservation(ownerToken, seatId1);

        mockMvc.perform(get("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationId));
    }

    @Test
    @DisplayName("사용자는 다른 사용자의 reservation을 조회할 수 없다")
    void user_cannot_get_other_users_reservation() throws Exception {
        Long reservationId = createReservation(ownerToken, seatId1);

        mockMvc.perform(get("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("사용자는 본인의 payment를 조회할 수 있다")
    void owner_can_get_own_payment() throws Exception {
        Long reservationId = createReservation(ownerToken, seatId1);
        Long paymentId = createPayment(ownerToken, reservationId);

        mockMvc.perform(get("/api/payments/" + paymentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId));
    }

    @Test
    @DisplayName("사용자는 다른 사용자의 payment를 조회할 수 없다")
    void user_cannot_get_other_users_payment() throws Exception {
        Long reservationId = createReservation(ownerToken, seatId1);
        Long paymentId = createPayment(ownerToken, reservationId);

        mockMvc.perform(get("/api/payments/" + paymentId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("존재하지 않는 payment 조회는 500이 아니라 404를 반환한다")
    void missing_payment_returns_not_found() throws Exception {
        mockMvc.perform(get("/api/payments/" + Long.MAX_VALUE)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 사용자는 reservation을 취소할 수 없고 원 예약 상태가 유지된다")
    void user_cannot_cancel_other_users_reservation() throws Exception {
        Long reservationId = createReservation(ownerToken, seatId1);

        mockMvc.perform(delete("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    private String signupAndLogin(String email) throws Exception {
        SignupRequest signupRequest = new SignupRequest(email, "password123", "테스터");
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest(email, "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("token").asText();
    }

    private Long createReservation(String jwt, Long seatId) throws Exception {
        String queueToken = issueQueueToken(jwt);
        Map<String, Object> request = Map.of(
                "scheduleId", scheduleId,
                "seatIds", List.of(seatId),
                "queueToken", queueToken
        );

        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "access-reservation-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long createPayment(String jwt, Long reservationId) throws Exception {
        PaymentRequest request = new PaymentRequest(reservationId);

        MvcResult result = mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "access-payment-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String issueQueueToken(String jwt) throws Exception {
        QueueEnterRequest enterRequest = new QueueEnterRequest(scheduleId);
        mockMvc.perform(post("/api/queue/enter")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(enterRequest)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/queue/token")
                        .header("Authorization", "Bearer " + jwt)
                        .param("scheduleId", String.valueOf(scheduleId)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode tokenNode = objectMapper.readTree(result.getResponse().getContentAsString());
        return tokenNode.get("token").asText();
    }
}
