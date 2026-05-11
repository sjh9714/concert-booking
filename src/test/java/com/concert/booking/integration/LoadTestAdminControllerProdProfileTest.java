package com.concert.booking.integration;

import com.concert.booking.config.TestContainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod"})
@Import(TestContainersConfig.class)
class LoadTestAdminControllerProdProfileTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("prod profile에서는 load-test admin endpoint가 노출되지 않는다")
    void loadTestAdminEndpoint_is_not_exposed_in_prod_profile() throws Exception {
        mockMvc.perform(post("/api/admin/load-test/reset")
                        .param("scheduleId", "1"))
                .andExpect(status().isNotFound());
    }
}
