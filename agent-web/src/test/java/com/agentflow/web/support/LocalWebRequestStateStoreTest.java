package com.agentflow.web.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class LocalWebRequestStateStoreTest {
    @Test void keepsRevocationAcrossStoreInstancesAndBoundsRateState() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:local-web-state;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table auth_access_revocation(jti varchar(128) primary key, expires_at timestamp not null)");
        Instant now = Instant.parse("2026-10-07T01:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var store = new LocalWebRequestStateStore(jdbc, clock);
        assertThat(store.isAccessRevoked("fresh-token")).isFalse();
        store.revokeAccess("revoked-token", now.plusSeconds(300));
        store.revokeAccess("revoked-token", now.plusSeconds(600));
        assertThat(new LocalWebRequestStateStore(jdbc, clock).isAccessRevoked("revoked-token")).isTrue();
        var afterExpiry = new LocalWebRequestStateStore(jdbc, Clock.fixed(now.plusSeconds(601), ZoneOffset.UTC));
        assertThat(afterExpiry.isAccessRevoked("revoked-token")).isFalse();
        for (int i = 1; i <= 121; i++) assertThat(store.incrementRate("client", now.plusSeconds(120))).isEqualTo(i);
        for (int i = 1; i < 4096; i++) store.incrementRate("client-" + i, now.plusSeconds(120));
        assertThatThrownBy(() -> store.incrementRate("new-client", now.plusSeconds(120))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.revokeAccess("x".repeat(129), now.plusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
    }
}
