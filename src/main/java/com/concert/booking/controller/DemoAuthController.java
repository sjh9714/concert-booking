package com.concert.booking.controller;

import com.concert.booking.common.exception.ResourceNotFoundException;
import com.concert.booking.common.jwt.JwtProvider;
import com.concert.booking.domain.User;
import com.concert.booking.dto.auth.LoginResponse;
import com.concert.booking.repository.UserRepository;
import com.concert.booking.service.auth.DemoAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Profile("(demo | e2e) & !prod")
@RequiredArgsConstructor
public class DemoAuthController {

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

    @PostMapping("/demo")
    public ResponseEntity<LoginResponse> loginDemoAccount() {
        User user = userRepository.findByEmail(DemoAccount.EMAIL)
                .orElseThrow(() -> new ResourceNotFoundException("데모 계정을 찾을 수 없습니다."));
        String token = jwtProvider.createToken(user.getId(), user.getEmail());
        return ResponseEntity.ok(new LoginResponse(
                token,
                user.getId(),
                user.getEmail(),
                user.getNickname()));
    }
}
