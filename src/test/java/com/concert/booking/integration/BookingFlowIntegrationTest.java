package com.concert.booking.integration;

import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.*;
import com.concert.booking.dto.auth.LoginRequest;
import com.concert.booking.dto.auth.SignupRequest;
import com.concert.booking.dto.payment.PaymentRequest;
import com.concert.booking.dto.reservation.ReservationRequest;
import com.concert.booking.repository.*;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class BookingFlowIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;

    private String token;
    private Long concertId;
    private Long scheduleId;
    private Long seatId1;
    private Long seatId2;

    @BeforeEach
    void setUp() throws Exception {
        // 테스트 데이터 생성
        Concert concert = Concert.create("테스트 콘서트", "설명", "테스트 장소", "테스트 아티스트");
        concertRepository.save(concert);
        concertId = concert.getId();

        ConcertSchedule schedule = ConcertSchedule.create(concert, LocalDate.now().plusDays(7), LocalTime.of(19, 0), 10);
        concertScheduleRepository.save(schedule);
        scheduleId = schedule.getId();

        Seat seat1 = Seat.create(schedule, "VIP", 1, 1, 150000);
        Seat seat2 = Seat.create(schedule, "VIP", 1, 2, 150000);
        seatRepository.save(seat1);
        seatRepository.save(seat2);
        seatId1 = seat1.getId();
        seatId2 = seat2.getId();

        // 회원가입 + 로그인
        String email = "flow-" + System.nanoTime() + "@test.com";
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

        token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    @DisplayName("전체 예매 흐름: 콘서트 조회 → 좌석 조회 → 예매 → 결제")
    void full_booking_flow() throws Exception {
        // 1. 콘서트 목록 조회
        mockMvc.perform(get("/api/concerts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 2. 좌석 조회
        mockMvc.perform(get("/api/concerts/" + concertId + "/schedules/" + scheduleId + "/seats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 3. 예매 (2석)
        ReservationRequest reservationRequest = new ReservationRequest(scheduleId, List.of(seatId1, seatId2));
        MvcResult reserveResult = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reservationRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(300000))
                .andReturn();

        Long reservationId = objectMapper.readTree(reserveResult.getResponse().getContentAsString()).get("id").asLong();

        // 4. 예매 상세 조회
        mockMvc.perform(get("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats").isArray())
                .andExpect(jsonPath("$.seats.length()").value(2));

        // 5. 결제
        PaymentRequest paymentRequest = new PaymentRequest(reservationId);
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(300000));

        // 6. 예매 상태 확인: CONFIRMED
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        // 7. 좌석 상태 확인: RESERVED
        Seat seat = seatRepository.findById(seatId1).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.RESERVED);
    }

    @Test
    @DisplayName("예매 취소 흐름: 예매 → 취소 → 좌석 반환")
    void cancel_reservation_flow() throws Exception {
        // 예매
        ReservationRequest reservationRequest = new ReservationRequest(scheduleId, List.of(seatId1));
        MvcResult reserveResult = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reservationRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        Long reservationId = objectMapper.readTree(reserveResult.getResponse().getContentAsString()).get("id").asLong();

        // 취소
        mockMvc.perform(delete("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 좌석 상태 확인: AVAILABLE
        Seat seat = seatRepository.findById(seatId1).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);

        // 예매 상태 확인: CANCELLED
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }
}
