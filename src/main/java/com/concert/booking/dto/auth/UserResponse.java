package com.concert.booking.dto.auth;

import com.concert.booking.domain.User;

public record UserResponse(Long userId, String email, String nickname) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getNickname());
    }
}
