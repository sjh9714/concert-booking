package com.concert.booking.config;

import com.concert.booking.domain.User;
import com.concert.booking.domain.UserRole;
import com.concert.booking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@Profile("local-monitoring")
@RequiredArgsConstructor
public class LocalMonitoringAdminBootstrap {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    CommandLineRunner localMonitoringAdminUser(
            @Value("${monitoring.admin.email:monitor-admin@local}") String email,
            @Value("${monitoring.admin.password:monitor-admin-local-password}") String password
    ) {
        return args -> userRepository.findByEmail(email)
                .ifPresentOrElse(existing -> {
                    if (existing.getRole() != UserRole.ADMIN) {
                        throw new IllegalStateException(
                                "local-monitoring admin email already exists without ADMIN role: " + email);
                    }
                }, () -> userRepository.save(User.createAdmin(
                        email,
                        passwordEncoder.encode(password),
                        "Local Monitoring Admin")));
    }
}
