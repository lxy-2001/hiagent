package com.agentflow.web.run;

import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

final class RunTestSupport {

    private RunTestSupport() {
    }

    static ControlledTime timeAt(Instant initialInstant) {
        return new ControlledTime(new AtomicReference<>(Objects.requireNonNull(initialInstant,
                "initialInstant must not be null")), new AtomicLong(), ZoneOffset.UTC);
    }

    static Jwt jwtFor(String ownerId) {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        if (ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        return Jwt.withTokenValue("test-token-" + ownerId)
                .header("alg", "none")
                .subject(ownerId)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofHours(1)))
                .build();
    }

    static final class ControlledTime extends Clock implements LongSupplier {

        private final AtomicReference<Instant> currentInstant;
        private final AtomicLong monotonicNanos;
        private final ZoneId zone;

        private ControlledTime(AtomicReference<Instant> currentInstant, AtomicLong monotonicNanos, ZoneId zone) {
            this.currentInstant = currentInstant;
            this.monotonicNanos = monotonicNanos;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            Objects.requireNonNull(requestedZone, "requestedZone must not be null");
            return zone.equals(requestedZone)
                    ? this
                    : new ControlledTime(currentInstant, monotonicNanos, requestedZone);
        }

        @Override
        public Instant instant() {
            return currentInstant.get();
        }

        @Override
        public long getAsLong() {
            return monotonicNanos.get();
        }

        void advance(Duration duration) {
            Objects.requireNonNull(duration, "duration must not be null");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("duration must not be negative");
            }
            currentInstant.updateAndGet(value -> value.plus(duration));
            monotonicNanos.updateAndGet(value -> Math.addExact(value, duration.toNanos()));
        }
    }
}
