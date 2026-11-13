package com.backroom.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Request correlation ids and quotable 5xx problems. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@Import(RequestIdIntegrationTests.FailureConfiguration.class)
class RequestIdIntegrationTests extends ApiTestSupport {
    static final String HEADER = "X-Request-Id";

    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void echoesAWellFormedIncomingRequestId() throws Exception {
        var incoming = "edge-router_1.abc-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(get("/actuator/health").header(HEADER, incoming))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, incoming));
    }

    @Test
    void generatesARequestIdWhenNoneIsSent() throws Exception {
        var first = mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HEADER);
        var second = mvc.perform(get("/api/v1/items"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getHeader(HEADER);
        assertThat(first).matches(UUID_PATTERN);
        assertThat(second).matches(UUID_PATTERN).isNotEqualTo(first);
    }

    @Test
    void replacesMalformedRequestIds() throws Exception {
        for (var malformed :
                new String[] {"a".repeat(65), "has space", "line\u0001break", "quote\"injection", "{json}", "日本"}) {
            var echoed = mvc.perform(get("/actuator/health").header(HEADER, malformed))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getHeader(HEADER);
            assertThat(echoed).as("replacement for %s", malformed).matches(UUID_PATTERN);
        }
        var longest = "a".repeat(64);
        mvc.perform(get("/actuator/health").header(HEADER, longest)).andExpect(header().string(HEADER, longest));
    }

    @Test
    void unexpectedFailuresReturnAQuotableRequestIdThatMatchesTheLogs(CapturedOutput output) throws Exception {
        var incoming = "support-" + UUID.randomUUID();
        var result = mvc.perform(get("/api/v1/test-only/failure")
                        .header(HEADER, incoming)
                        .with(user("test-only")))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HEADER, incoming))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").value(incoming))
                .andReturn();
        var body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Simulated failure").doesNotContain("Exception");
        // Plain-text logs use [requestId]; a prior prod-profile boot in this JVM can leave ECS JSON console logging on.
        var logs = output.getAll();
        assertThat(logs).contains("An unexpected application failure occurred");
        assertThat(logs)
                .satisfiesAnyOf(
                        text -> assertThat(text).contains("[" + incoming + "]"),
                        text -> assertThat(text).contains("\"requestId\":\"" + incoming + "\""));

        var generated = mvc.perform(get("/api/v1/test-only/failure").with(user("test-only")))
                .andExpect(status().isInternalServerError())
                .andReturn()
                .getResponse();
        assertThat(tree(generated.getContentAsString()).get("requestId").asString())
                .isEqualTo(generated.getHeader(HEADER))
                .matches(UUID_PATTERN);
    }

    @Test
    void clientErrorsDoNotCarryARequestIdProperty() throws Exception {
        mvc.perform(get("/api/v1/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").doesNotExist());
    }

    private tools.jackson.databind.JsonNode tree(String text) {
        return mapper.readTree(text);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureConfiguration {
        @Bean
        FailingController failingController() {
            return new FailingController();
        }
    }

    @RestController
    static class FailingController {
        @GetMapping("/api/v1/test-only/failure")
        String fail() {
            throw new IllegalStateException("Simulated failure with private detail");
        }
    }
}
