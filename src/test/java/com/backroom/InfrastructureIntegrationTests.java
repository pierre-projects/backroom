package com.backroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backroom.support.IntegrationTest;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Real PostgreSQL and Flyway, security defaults, and what the actuator exposes. */
@IntegrationTest
class InfrastructureIntegrationTests {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Test
    void connectsToRealPostgresAndInitializesFlywayHistory() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.flyway_schema_history')::text", String.class))
                .isEqualTo("flyway_schema_history");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
        // A second migration pass must be safe against the existing database.
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void exposesOnlyMinimalHealth() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void rejectsAnonymousApiAccessWithoutRedirectingToLogin() throws Exception {
        mvc.perform(get("/api/v1/items")).andExpect(status().isUnauthorized());
    }

    @Test
    void keepsCsrfProtectionEnabled() throws Exception {
        mvc.perform(post("/api/v1/items").with(user("test-only"))).andExpect(status().isForbidden());
    }

    @Test
    void doesNotExposeSensitiveActuatorEndpointsEvenToAuthenticatedUsers() throws Exception {
        for (var endpoint : new String[] {"env", "configprops", "beans", "mappings", "heapdump"}) {
            mvc.perform(get("/actuator/" + endpoint).with(user("test-only"))).andExpect(status().isForbidden());
        }
    }

    @Test
    void exposesAnonymousProbesWithoutDetails() throws Exception {
        for (var probe : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            mvc.perform(get(probe))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist())
                    .andExpect(jsonPath("$.details").doesNotExist());
        }
        // Individual components stay closed even though the probe groups are open.
        mvc.perform(get("/actuator/health/db")).andExpect(status().is4xxClientError());
        mvc.perform(get("/actuator/health/db").with(user("test-only"))).andExpect(status().isForbidden());
    }
}
