package com.backroom.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Browser routes forward to the single-page app; unknown API paths and assets return problems. */
@IntegrationTest
class SpaRoutingIntegrationTests extends ApiTestSupport {
    @Test
    void preservesSpaRoutesAndReturnsProblemsForUnknownApiAndAssets() throws Exception {
        var user = register();
        mvc.perform(get("/app/organizations/" + UUID.randomUUID() + "/items"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        for (var page : List.of("/terms", "/privacy", "/security", "/contact"))
            mvc.perform(get(page)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        assertThat(call(user, get("/api/v1/unknown"), null).status()).isEqualTo(404);
        mvc.perform(get("/assets/missing.js"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
