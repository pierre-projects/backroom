package com.backroom.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestIdFilterTests {
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
