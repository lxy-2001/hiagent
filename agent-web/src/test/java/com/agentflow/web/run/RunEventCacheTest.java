package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RunEventCacheTest {

    private final AtomicLong ticker = new AtomicLong();

    @Test
    void boundsActiveRunsAndReleasesAnActiveSlotWhenRunBecomesTerminal() {
        InMemoryRunEventHub hub = hub(2, 2, 100);

        assertThat(hub.create("run-1")).isTrue();
        assertThat(hub.create("run-2")).isTrue();
        assertThat(hub.create("run-3")).isFalse();
        assertThat(hub.activeCount()).isEqualTo(2);

        assertThat(hub.markTerminal("run-1")).isTrue();
        assertThat(hub.create("run-3")).isTrue();
        assertThat(hub.activeCount()).isEqualTo(2);
        assertThat(hub.terminalCount()).isEqualTo(1);
    }

    @Test
    void evictsOldestTerminalRunAtCapacityAndDoesNotKeepTombstones() {
        InMemoryRunEventHub hub = hub(3, 2, 100);
        for (int i = 1; i <= 3; i++) {
            String taskId = "run-" + i;
            assertThat(hub.create(taskId)).isTrue();
            hub.publish(taskId, draft(taskId));
            assertThat(hub.markTerminal(taskId)).isTrue();
            ticker.incrementAndGet();
        }

        assertThat(hub.terminalCount()).isEqualTo(2);
        assertThat(hub.cachedRunCount()).isEqualTo(2);
        assertThat(hub.replay("run-1", 0).status())
                .isEqualTo(RunEventHub.ReplayStatus.UNAVAILABLE);
        assertThat(hub.replay("run-2", 0).status())
                .isEqualTo(RunEventHub.ReplayStatus.AVAILABLE);
    }

    @Test
    void expiresTerminalRunsAtTtlAndClearsTheirFrames() {
        InMemoryRunEventHub hub = hub(2, 2, 10);
        hub.create("run-1");
        hub.publish("run-1", draft("run-1"));
        hub.markTerminal("run-1");

        ticker.set(9);
        hub.maintain();
        assertThat(hub.replay("run-1", 0).status()).isEqualTo(RunEventHub.ReplayStatus.AVAILABLE);

        ticker.set(10);
        hub.maintain();
        assertThat(hub.replay("run-1", 0).status()).isEqualTo(RunEventHub.ReplayStatus.UNAVAILABLE);
        assertThat(hub.cachedRunCount()).isZero();
    }

    @Test
    void missingPublishNeverCreatesAHubAndTerminalRunsIgnoreLateAppend() {
        InMemoryRunEventHub hub = hub(2, 2, 100);

        assertThat(hub.publish("missing", draft("missing")).status())
                .isEqualTo(RunEventHub.PublishStatus.MISSING);
        assertThat(hub.cachedRunCount()).isZero();

        hub.create("run-1");
        hub.publish("run-1", draft("run-1"));
        hub.markTerminal("run-1");
        assertThat(hub.publish("run-1", draft("run-1")).status())
                .isEqualTo(RunEventHub.PublishStatus.TERMINAL);
        assertThat(hub.replay("run-1", 0).frames()).hasSize(1);
    }

    private InMemoryRunEventHub hub(int active, int terminal, long ttl) {
        return new InMemoryRunEventHub(new ObjectMapper(), 8, 16_000, 4_000,
                active, terminal, ttl, ticker::get);
    }

    private static RunEvent.Draft draft(String taskId) {
        return new RunEvent.Draft(taskId, taskId, RunEvent.Type.AGENT_STEP,
                Instant.parse("2026-09-15T10:00:00Z"),
                new RunEvent.AgentStepPayload("1", "LLM", "model", "safe",
                        null, false, false));
    }
}
