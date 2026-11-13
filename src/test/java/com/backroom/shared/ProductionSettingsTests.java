package com.backroom.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LoggingSystem;

class ProductionSettingsTests {
    @Test
    void requiresAPermanentHttpsPublicUrl() {
        for (var unusable : new String[] {
            null,
            "",
            "  ",
            "http://stock.example.com",
            "stock.example.com",
            "https://",
            "https://stock.example.com/?a=1",
            "not a url"
        }) {
            assertThatIllegalStateException()
                    .as("value %s", unusable)
                    .isThrownBy(() -> ProductionSettings.requireHttpsPublicUrl(unusable))
                    .withMessageContaining("BACKROOM_PUBLIC_URL");
        }
        assertThatNoException().isThrownBy(() -> ProductionSettings.requireHttpsPublicUrl("https://stock.example.com"));
        assertThatNoException()
                .isThrownBy(() -> ProductionSettings.requireHttpsPublicUrl("https://example.com/backroom/"));
    }

    @Test
    void prodStartupFailsBeforeTouchingTheDatabaseWhenThePublicUrlIsMissing() {
        // The datasource points at a closed port: reaching it would fail with a connection error instead.
        var application = new org.springframework.boot.builder.SpringApplicationBuilder(
                        com.backroom.BackroomApplication.class)
                .profiles("prod")
                .logStartupInfo(false);
        try {
            assertThatThrownBy(() -> application.run(
                            "--backroom.public-base-url=",
                            "--server.port=0",
                            "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
                            "--spring.main.banner-mode=off"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BACKROOM_PUBLIC_URL must be set");
        } finally {
            // Prod enables ECS JSON console logging for this JVM; drop it so later tests see the default pattern again.
            LoggingSystem.get(getClass().getClassLoader()).cleanUp();
        }
    }

    @Test
    void acceptsOnlyShortSafeRequestIds() {
        assertThat(RequestIdFilter.valid("abc-123_x.y")).isTrue();
        assertThat(RequestIdFilter.valid("a".repeat(64))).isTrue();
        assertThat(RequestIdFilter.valid("a".repeat(65))).isFalse();
        assertThat(RequestIdFilter.valid("")).isFalse();
        assertThat(RequestIdFilter.valid(null)).isFalse();
        assertThat(RequestIdFilter.valid("a b")).isFalse();
        assertThat(RequestIdFilter.valid("a\nb")).isFalse();
    }
}
