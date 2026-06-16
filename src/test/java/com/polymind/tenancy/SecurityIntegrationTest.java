package com.polymind.tenancy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "polymind.tenancy.auth-enabled=true",
        "polymind.tenancy.admin-key=pmk-admin-test"
})
class SecurityIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/v1/health")).andExpect(status().isOk());
    }

    @Test
    void modelsRequireAuth() throws Exception {
        mvc.perform(get("/v1/models")).andExpect(status().isUnauthorized());
    }

    @Test
    void modelsSucceedWithValidKey() throws Exception {
        mvc.perform(get("/v1/models").header("Authorization", "Bearer pmk-admin-test"))
                .andExpect(status().isOk());
    }

    @Test
    void adminRequiresAdminKey() throws Exception {
        // admin key has ROLE_ADMIN, so it is allowed.
        mvc.perform(get("/v1/admin/keys").header("Authorization", "Bearer pmk-admin-test"))
                .andExpect(status().isOk());
    }

    @Test
    void adminRejectsMissingKey() throws Exception {
        mvc.perform(get("/v1/admin/keys")).andExpect(status().isUnauthorized());
    }
}
