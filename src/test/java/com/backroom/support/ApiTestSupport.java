package com.backroom.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared HTTP helpers for integration tests: real sessions, CSRF, JSON calls and coordinated concurrency.
 * Subclasses are annotated with {@link IntegrationTest}.
 */
public abstract class ApiTestSupport {
    protected static final String PASSWORD = "Correct horse staple 42!";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected JdbcTemplate jdbc;

    public record Client(UUID id, MockHttpSession session, String csrf, String email) {}

    public record Response(int status, JsonNode json) {
        public String code() {
            return json.has("errorCode") ? json.get("errorCode").asString() : "";
        }
    }

    /**
     * Registers and signs in a new person. Registration uses an upper-case email and sign-in pads it with spaces, so
     * every test also exercises email normalization; the session id must change on sign-in (fixation protection).
     */
    protected Client register() throws Exception {
        var email = "person-" + UUID.randomUUID() + "@example.test";
        var csrfResult =
                mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        var csrf = tree(csrfResult).get("token").asString();
        var input = Map.of("email", email.toUpperCase(Locale.ROOT), "displayName", "Test Person", "password", PASSWORD);
        var created = mvc.perform(post("/api/v1/auth/register")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andReturn();
        var anonymousId = session.getId();
        mvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .with(request -> {
                            request.setRequestedSessionId(session.getId());
                            request.setRequestedSessionIdValid(true);
                            return request;
                        })
                        .param("email", " " + email.toUpperCase(Locale.ROOT) + " ")
                        .param("password", PASSWORD))
                .andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(anonymousId);
        var refreshed = mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        return new Client(
                UUID.fromString(tree(created).get("id").asString()),
                session,
                tree(refreshed).get("token").asString(),
                email);
    }

    protected JsonNode tree(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    protected Response call(Client client, MockHttpServletRequestBuilder request, Object input) throws Exception {
        request.session(client.session()).header("X-CSRF-TOKEN", client.csrf());
        if (input != null) request.contentType("application/json").content(mapper.writeValueAsString(input));
        var result = mvc.perform(request).andReturn();
        var text = result.getResponse().getContentAsString();
        return new Response(
                result.getResponse().getStatus(), text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text));
    }

    protected JsonNode ok(Client client, MockHttpServletRequestBuilder request, Object input) throws Exception {
        var response = call(client, request, input);
        assertThat(response.status())
                .withFailMessage(response.json().toString())
                .isBetween(200, 299);
        return response.json();
    }

    protected JsonNode org(Client client) throws Exception {
        return ok(client, post("/api/v1/organizations"), Map.of("name", "Business " + UUID.randomUUID()));
    }

    protected String base(JsonNode org) {
        return "/api/v1/organizations/" + org.get("id").asString();
    }

    /** Creates a ROOM at the top level, or a SHELF under {@code parentId}. */
    protected JsonNode location(Client client, String base, String name, String parentId) throws Exception {
        var data = new HashMap<String, Object>();
        data.put("name", name);
        data.put("type", parentId == null ? "ROOM" : "SHELF");
        data.put("parentId", parentId);
        return ok(client, post(base + "/locations"), data);
    }

    protected JsonNode item(Client client, String base, String name, String unit) throws Exception {
        return ok(client, post(base + "/items"), Map.of("name", name, "unit", unit));
    }

    protected String invitation(Client owner, String base, String role) throws Exception {
        return ok(owner, post(base + "/invitations"), Map.of("role", role))
                .get("invitePath")
                .asString()
                .split("token=")[1];
    }

    protected void join(Client user, String token) throws Exception {
        ok(user, post("/api/v1/invitations/accept"), Map.of("token", token));
    }

    /** Starts every task at the same moment on its own thread (and therefore its own transaction and connection). */
    @SafeVarargs
    protected final List<Integer> race(Callable<Integer>... tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(tasks.length)) {
            var futures = new ArrayList<Future<Integer>>();
            for (var task : tasks)
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : futures) statuses.add(future.get(30, TimeUnit.SECONDS));
            return statuses;
        }
    }

    /**
     * Waits until PostgreSQL reports at least {@code count} sessions waiting for a lock. This is how tests confirm
     * that a competing transaction is genuinely blocked behind a held one, rather than guessing with sleeps.
     */
    protected void awaitBlockedOnLocks(int count) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            var waiting = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting >= count) return;
            Thread.onSpinWait();
            TimeUnit.MILLISECONDS.sleep(20);
        }
        fail("Expected " + count + " transaction(s) to wait on a lock.");
    }
}
