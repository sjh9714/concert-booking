package com.concert.booking.integration;

import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.service.outbox.OutboxRelayScheduler;
import com.concert.booking.service.reservation.ReservationExpirationScheduler;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "outbox.relay.enabled=true")
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class SchedulerConfigIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Scheduler와 ShedLock 설정 bean이 로드된다")
    void scheduler_and_shedlock_beans_are_loaded() {
        assertThat(applicationContext.getBeanNamesForType(ReservationExpirationScheduler.class))
                .hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(OutboxRelayScheduler.class))
                .hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(LockProvider.class))
                .hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .hasSize(1);
    }
}
