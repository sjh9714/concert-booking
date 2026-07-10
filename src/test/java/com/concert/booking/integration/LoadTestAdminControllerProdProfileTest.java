package com.concert.booking.integration;

import com.concert.booking.config.TestContainersConfig;
import com.concert.booking.config.DataInitializer;
import com.concert.booking.config.LocalMonitoringAdminBootstrap;
import com.concert.booking.controller.DemoAuthController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod", "demo", "e2e", "load-test", "local-monitoring"})
@Import(TestContainersConfig.class)
class LoadTestAdminControllerProdProfileTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext applicationContext;

    @Test
    @DisplayName("prod profile에서는 load-test admin endpoint가 노출되지 않는다")
    void loadTestAdminEndpoint_is_not_exposed_in_prod_profile() throws Exception {
        mockMvc.perform(post("/api/admin/load-test/reset")
                        .param("scheduleId", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("prod와 로컬 profile을 함께 활성화해도 demo 인증과 seed bean은 로드되지 않는다")
    void localOnlyBeans_are_not_loaded_when_prod_is_also_active() throws Exception {
        assertThat(applicationContext.getBeansOfType(DemoAuthController.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DataInitializer.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(LocalMonitoringAdminBootstrap.class)).isEmpty();

        mockMvc.perform(post("/api/auth/demo"))
                .andExpect(status().isNotFound());
    }
}
