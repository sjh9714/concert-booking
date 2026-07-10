package com.concert.booking.controller;

import com.concert.booking.dto.auth.UserResponse;
import com.concert.booking.service.auth.CustomUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(UserResponse.from(userDetails.user()));
    }
}
