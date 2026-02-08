package com.concert.booking.dto.auth;

public record LoginResponse(
        String token,
        Long userId,
        String email,
        String nickname
) {
}
