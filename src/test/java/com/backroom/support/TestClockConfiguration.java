package com.backroom.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application clock with one tests can move forward, for expiry rules. Reset it after each test. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {
    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    public static class MutableClock extends Clock {
        private static final Instant INITIAL = Instant.parse("2026-11-12T03:00:00Z");
        private final AtomicReference<Instant> now = new AtomicReference<>(INITIAL);

        public void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        public void reset() {
            now.set(INITIAL);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
