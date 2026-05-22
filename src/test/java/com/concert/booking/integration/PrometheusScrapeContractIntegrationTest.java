package com.concert.booking.integration;

import com.concert.booking.common.exception.InvalidQueueTokenException;
import com.concert.booking.common.jwt.JwtProvider;
import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.domain.User;
import com.concert.booking.observability.BookingMetrics;
import com.concert.booking.observability.OutboxMetricsSnapshot;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "outbox.relay.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class PrometheusScrapeContractIntegrationTest {

    private static final Pattern PROMETHEUS_METRIC_NAME =
            Pattern.compile("concert_booking_[a-zA-Z0-9_]+");

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BookingMetrics bookingMetrics;
    @Autowired private OutboxMetricsSnapshot outboxMetricsSnapshot;

    private String adminToken;

    @BeforeEach
    void setUp() {
        User admin = userRepository.save(User.createAdmin(
                "prometheus-admin-" + System.nanoTime() + "@test.com",
                passwordEncoder.encode("password123"),
                "관리자"));

        adminToken = jwtProvider.createToken(admin.getId(), admin.getEmail());
    }

    @Test
    @DisplayName("Prometheus actuator scrape는 alert/dashboard가 참조하는 metric name을 노출한다")
    void prometheus_scrape_exposes_metric_names_referenced_by_monitoring_templates() throws Exception {
        registerMetricsReferencedByMonitoringTemplates();

        String scrape = mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Set<String> referencedMetricNames = referencedMetricNames();

        assertThat(referencedMetricNames)
                .as("monitoring templates must keep explicit metric references")
                .contains(
                        "concert_booking_outbox_events",
                        "concert_booking_queue_token_validation_failures_total",
                        "concert_booking_reservation_attempts_total",
                        "concert_booking_reservation_failures_total",
                        "concert_booking_reservation_latency_seconds_max",
                        "concert_booking_reservation_success_total",
                        "concert_booking_stock_reconciliation_mismatches_total",
                        "concert_booking_stock_reconciliation_repairs_total"
                );

        for (String metricName : referencedMetricNames) {
            assertThat(scrape)
                    .as("missing Prometheus metric referenced by monitoring templates: %s", metricName)
                    .contains(metricName);
        }
    }

    private void registerMetricsReferencedByMonitoringTemplates() {
        bookingMetrics.recordReservation(() -> "ok");

        try {
            bookingMetrics.recordReservation(() -> {
                throw new InvalidQueueTokenException("유효하지 않은 입장 토큰입니다.");
            });
        } catch (InvalidQueueTokenException ignored) {
            // expected: registers reservation failure and latency series
        }

        bookingMetrics.recordQueueTokenValidationFailure();
        bookingMetrics.recordStockReconciliationMismatch();
        bookingMetrics.recordStockReconciliationRepair();
        outboxMetricsSnapshot.refresh();
    }

    private Set<String> referencedMetricNames() throws Exception {
        Set<String> metricNames = new TreeSet<>();

        for (Path path : Set.of(
                Path.of("monitoring/alert-rules.yml"),
                Path.of("monitoring/grafana/dashboards/concert-booking-dashboard.json")
        )) {
            Matcher matcher = PROMETHEUS_METRIC_NAME.matcher(Files.readString(path));
            while (matcher.find()) {
                metricNames.add(matcher.group());
            }
        }

        return metricNames;
    }
}
