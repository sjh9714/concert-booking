package com.concert.booking.integration;

import com.concert.booking.common.jwt.JwtProvider;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.User;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class ActuatorSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private PasswordEncoder passwordEncoder;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.create(
                "actuator-user-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "일반사용자"));
        User admin = userRepository.save(User.createAdmin(
                "actuator-admin-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "관리자"));

        userToken = jwtProvider.createToken(user.getId(), user.getEmail());
        adminToken = jwtProvider.createToken(admin.getId(), admin.getEmail());
    }

    @Test
    @DisplayName("health와 info actuator endpoint는 인증 없이 조회할 수 있다")
    void health_and_info_are_public() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("metrics와 prometheus actuator endpoint는 ADMIN 권한이 필요하다")
    void metrics_and_prometheus_require_admin_role() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
