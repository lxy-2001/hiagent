package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RunEventWindowTest {

    @Test
    void assignsIdsAndReplaysTheExactPreviouslyEncodedBytes() {
        InMemoryRunEventHub hub = hub(256, 1_048_576, 16_384);
        assertThat(hub.create("run-1")).isTrue();

        RunEventHub.PublishedFrame first = hub.publish("run-1", draft("run-1", "one")).frame();
        hub.publish("run-1", draft("run-1", "two"));
        RunEventHub.ReplayResult replay = hub.replay("run-1", 0);

        assertThat(first.event().eventId()).isEqualTo("1");
        assertThat(replay.status()).isEqualTo(RunEventHub.ReplayStatus.AVAILABLE);
        assertThat(replay.frames()).extracting(frame -> frame.event().eventId())
                .containsExactly("1", "2");
        assertThat(replay.frames().get(0).jsonBytes()).containsExactly(first.jsonBytes());
        assertThat(replay.frames().get(0).sseBytes()).containsExactly(first.sseBytes());
        assertThat(new String(first.sseBytes(), StandardCharsets.UTF_8))
                .startsWith("id: 1\nevent: AGENT_STEP\ndata: ").endsWith("\n\n");
    }

    @Test
    void enforcesEarliestMinusOneThroughLatestCursorBoundaries() {
        InMemoryRunEventHub hub = hub(3, 1_048_576, 16_384);
        hub.create("run-1");
        for (int i = 1; i <= 4; i++) {
            hub.publish("run-1", draft("run-1", "event-" + i));
        }

        assertThat(hub.replay("run-1", 0).status()).isEqualTo(RunEventHub.ReplayStatus.TOO_OLD);
        RunEventHub.ReplayResult fromBoundary = hub.replay("run-1", 1);
        assertThat(fromBoundary.earliestId()).isEqualTo(2);
        assertThat(fromBoundary.latestId()).isEqualTo(4);
        assertThat(fromBoundary.frames()).extracting(frame -> frame.event().eventId())
                .containsExactly("2", "3", "4");
        assertThat(hub.replay("run-1", 4).frames()).isEmpty();
        assertThat(hub.replay("run-1", 5).status()).isEqualTo(RunEventHub.ReplayStatus.AHEAD);
    }

    @Test
    void appliesCountWindowByteWindowAndFrameLimitTogether() {
        InMemoryRunEventHub hub = hub(3, 1_100, 700);
        hub.create("run-1");
        hub.publish("run-1", draft("run-1", "中".repeat(100)));
        hub.publish("run-1", draft("run-1", "文".repeat(100)));
        hub.publish("run-1", draft("run-1", "字".repeat(100)));

        RunEventHub.ReplayResult replay = hub.replay("run-1", 0);
        assertThat(replay.status()).isEqualTo(RunEventHub.ReplayStatus.TOO_OLD);
        assertThat(replay.earliestId()).isGreaterThan(1);

        RunEventHub.PublishResult oversized =
                hub.publish("run-1", draft("run-1", "x".repeat(1_000)));
        assertThat(oversized.status()).isEqualTo(RunEventHub.PublishStatus.UNAVAILABLE);
        assertThat(hub.replay("run-1", replay.latestId()).status())
                .isEqualTo(RunEventHub.ReplayStatus.UNAVAILABLE);
    }

    @Test
    void serializesConcurrentPublishersInsideTheIdAndWindowCriticalSection() throws Exception {
        InMemoryRunEventHub hub = hub(32, 32_000, 4_000);
        hub.create("run-1");
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int value = i;
                calls.add(() -> hub.publish("run-1", draft("run-1", "e" + value))
                        .frame().event().eventId());
            }
            Set<String> ids = new HashSet<>();
            executor.invokeAll(calls).forEach(future -> {
                try {
                    ids.add(future.get());
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            assertThat(ids).hasSize(20).contains("1", "20");
            assertThat(hub.replay("run-1", 0).frames()).hasSize(20);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void supportsMaximumInt64IdThenMakesOverflowedStreamUnavailable() {
        InMemoryRunEventHub hub = new InMemoryRunEventHub(new ObjectMapper(), 3,
                10_000, 4_000, Long.MAX_VALUE - 1);
        hub.create("run-1");

        RunEventHub.PublishResult maximum = hub.publish("run-1", draft("run-1", "last"));
        RunEventHub.PublishResult overflow = hub.publish("run-1", draft("run-1", "overflow"));

        assertThat(maximum.frame().event().eventId()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(overflow.status()).isEqualTo(RunEventHub.PublishStatus.UNAVAILABLE);
        assertThat(hub.replay("run-1", Long.MAX_VALUE).status())
                .isEqualTo(RunEventHub.ReplayStatus.UNAVAILABLE);
    }

    private static InMemoryRunEventHub hub(int count, int bytes, int frameBytes) {
        return new InMemoryRunEventHub(new ObjectMapper(), count, bytes, frameBytes);
    }

    private static RunEvent.Draft draft(String taskId, String summary) {
        return new RunEvent.Draft(taskId, taskId, RunEvent.Type.AGENT_STEP,
                Instant.parse("2026-09-15T10:00:00Z"),
                new RunEvent.AgentStepPayload("0", "LLM", "model", summary,
                        null, false, false));
    }
}
