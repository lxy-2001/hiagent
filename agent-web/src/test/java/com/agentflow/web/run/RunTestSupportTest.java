package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunTestSupportTest {

    @Test
    void advancesWallAndMonotonicTimeTogetherWithoutSleeping() {
        Instant initial = Instant.parse("2026-09-15T08:00:00Z");
        RunTestSupport.ControlledTime time = RunTestSupport.timeAt(initial);

        time.advance(Duration.ofMillis(250));

        assertThat(time.instant()).isEqualTo(initial.plusMillis(250));
        assertThat(time.getAsLong()).isEqualTo(250_000_000L);
        assertThat(time.withZone(ZoneId.of("Asia/Shanghai")).instant()).isEqualTo(time.instant());
        assertThatThrownBy(() -> time.advance(Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsDistinctJwtOwnersForAuthorizationTests() {
        Jwt first = RunTestSupport.jwtFor("owner-1");
        Jwt second = RunTestSupport.jwtFor("owner-2");

        assertThat(first.getSubject()).isEqualTo("owner-1");
        assertThat(second.getSubject()).isEqualTo("owner-2");
        assertThat(first.getTokenValue()).isNotEqualTo(second.getTokenValue());
        assertThat(first.getExpiresAt()).isAfter(first.getIssuedAt());
    }
}
