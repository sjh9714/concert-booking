package com.concert.booking.integration;

import com.concert.booking.common.jwt.JwtProvider;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.Concert;
import com.concert.booking.domain.ConcertSchedule;
import com.concert.booking.domain.Seat;
import com.concert.booking.domain.User;
import com.concert.booking.repository.ConcertRepository;
import com.concert.booking.repository.ConcertScheduleRepository;
import com.concert.booking.repository.SeatRepository;
import com.concert.booking.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class AdminSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private ConcertRepository concertRepository;
    @Autowired private ConcertScheduleRepository concertScheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Long scheduleId;
    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        Concert concert = concertRepository.save(
                Concert.create("Admin 보안 테스트 콘서트", "설명", "장소", "아티스트"));
        ConcertSchedule schedule = concertScheduleRepository.save(
                ConcertSchedule.create(concert, LocalDate.now().plusDays(40), LocalTime.of(20, 0), 2));
        seatRepository.saveAll(List.of(
                Seat.create(schedule, "A", 1, 1, 100000),
                Seat.create(schedule, "A", 1, 2, 100000)));
        scheduleId = schedule.getId();

        User user = userRepository.save(User.create(
                "admin-security-user-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "일반사용자"));
        User admin = userRepository.save(User.createAdmin(
                "admin-security-admin-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "관리자"));

        userToken = jwtProvider.createToken(user.getId(), user.getEmail());
        adminToken = jwtProvider.createToken(admin.getId(), admin.getEmail());
    }

    @Test
    @DisplayName("일반 사용자는 admin reset, DLT replay, stock reconcile endpoint를 호출할 수 없다")
    void user_cannot_call_admin_endpoints() throws Exception {
        mockMvc.perform(post("/api/admin/reset")
                        .header("Authorization", "Bearer " + userToken)
                        .param("scheduleId", String.valueOf(scheduleId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/dlt/replay")
                        .header("Authorization", "Bearer " + userToken)
                        .param("topic", "reservation.cancelled.DLT")
                        .param("limit", "0"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/schedules/{scheduleId}/stock/reconcile", scheduleId)
                        .header("Authorization", "Bearer " + userToken)
                        .param("repair", "false"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 권한 사용자는 일반 admin utility를 호출할 수 있다")
    void admin_can_call_admin_endpoint() throws Exception {
        mockMvc.perform(post("/api/admin/schedules/{scheduleId}/stock/reconcile", scheduleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("repair", "false"))
                .andExpect(status().isOk());
    }
}
