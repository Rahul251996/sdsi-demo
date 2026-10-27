package com.digithon.assignment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Test configuration providing a controllable Clock.
 * Tests inject the FixedClock bean and call advance() to manipulate time.
 */
@TestConfiguration
public class TestConfig {

    /** Base time for all tests: 2026-10-27T10:00:00Z */
    public static final Instant START = Instant.parse("2026-10-27T10:00:00Z");

    @Bean
    @Primary
    public Clock clock() {
        return new FixedClock(START);
    }

    /**
     * A mutable Clock for deterministic expiry tests.
     * Call advance(Duration) to move time forward.
     */
    public static class FixedClock extends Clock {

        private volatile Instant instant;
        private final ZoneId zone;

        public FixedClock(Instant instant) {
            this.instant = instant;
            this.zone = ZoneId.of("UTC");
        }

        public void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        public void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        public Instant getInstant() {
            return instant;
        }
    }
}