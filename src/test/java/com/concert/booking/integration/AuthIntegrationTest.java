package com.concert.booking.integration;

import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.dto.auth.LoginRequest;
import com.concert.booking.dto.auth.SignupRequest;
import com.concert.booking.common.util.RedisKeyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.redis.core.RedisTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Test
    @DisplayName("회원가입 성공")
    void signup_success() throws Exception {
        SignupRequest request = new SignupRequest("auth-test@test.com", "password123", "테스터");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("회원가입 후 로그인 성공")
    void signup_and_login_success() throws Exception {
        SignupRequest signupRequest = new SignupRequest("login-test@test.com", "password123", "테스터2");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("login-test@test.com", "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("login-test@test.com"));
    }

    @Test
    @DisplayName("잘못된 비밀번호로 로그인 실패")
    void login_with_wrong_password() throws Exception {
        SignupRequest signupRequest = new SignupRequest("wrong-pw@test.com", "password123", "테스터3");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("wrong-pw@test.com", "wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("중복 이메일 회원가입 실패")
    void signup_duplicate_email() throws Exception {
        SignupRequest request = new SignupRequest("dup@test.com", "password123", "테스터4");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("공연 카탈로그는 공개하고 내 정보는 인증된 사용자에게만 제공한다")
    void public_catalog_and_authenticated_me() throws Exception {
        mockMvc.perform(get("/api/concerts"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        SignupRequest signup = new SignupRequest("me-test@test.com", "password123", "내정보테스터");
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated());

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("me-test@test.com", "password123"))))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).get("token").asText();

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me-test@test.com"))
                .andExpect(jsonPath("$.nickname").value("내정보테스터"));

        long missingScheduleId = Long.MAX_VALUE;
        mockMvc.perform(post("/api/queue/enter")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":" + missingScheduleId + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        org.assertj.core.api.Assertions.assertThat(
                        redisTemplate.hasKey(RedisKeyUtil.queueKey(missingScheduleId)))
                .isFalse();
    }
}
