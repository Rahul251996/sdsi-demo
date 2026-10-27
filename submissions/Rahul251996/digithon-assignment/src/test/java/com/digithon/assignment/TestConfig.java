package com.digithon.assignment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Test configuration providing a controllable Clock bean.
 * Tests can inject FixedClock and advance time as needed.
 */
@TestConfiguration
public class TestConfig {

    public static final Instant START = Instant.parse("2026-10-27T10:00:00Z");

    @Bean
    public Clock clock() {
        return new FixedClock(START);
    }

    /**
     * A Clock whose instant can be advanced during tests.
     */
    public static class FixedClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        public FixedClock(Instant instant) {
            this.instant = instant;
            this.zone = ZoneId.of("UTC");
        }

        public void advance(java.time.Duration duration) {
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
    }
}