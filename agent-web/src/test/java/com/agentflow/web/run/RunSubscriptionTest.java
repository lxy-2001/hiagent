package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunSubscriptionTest {
    @Test
    void subscriptionReadsHistoryAndLiveFramesFromOneCursorWithoutCopyingAQueue() throws Exception {
        InMemoryRunEventHub hub = hub(2, 3);
        hub.create("task");
        hub.publish("task", event("task", RunEvent.Type.RUN_CREATED));
        RunEventHub.OpenResult opened = hub.open("task", 0);

        assertThat(opened.status()).isEqualTo(RunEventHub.OpenStatus.OPEN);
        RunEventHub.ReadResult first = opened.subscription().read(0, Duration.ZERO);
        assertThat(first.status()).isEqualTo(RunEventHub.ReadStatus.FRAME);
        assertThat(first.frame().event().eventId()).isEqualTo("1");
        assertThat(opened.subscription().read(1, Duration.ZERO).status())
                .isEqualTo(RunEventHub.ReadStatus.IDLE);

        hub.publish("task", event("task", RunEvent.Type.RUN_STARTED));
        assertThat(opened.subscription().read(1, Duration.ofMillis(10)).frame().event().eventId())
                .isEqualTo("2");
        hub.markTerminal("task");
        assertThat(opened.subscription().read(2, Duration.ZERO).status())
                .isEqualTo(RunEventHub.ReadStatus.DONE);
        opened.subscription().close();
        assertThat(hub.subscriptionCount()).isZero();
        assertThat(hub.open("task", 2).status()).isEqualTo(RunEventHub.OpenStatus.DONE);
    }

    @Test
    void validatesCursorBeforeAtomicallyReservingPerRunAndGlobalCapacity() {
        InMemoryRunEventHub hub = hub(1, 2);
        hub.create("one");
        hub.create("two");
        hub.publish("one", event("one", RunEvent.Type.RUN_CREATED));
        hub.publish("two", event("two", RunEvent.Type.RUN_CREATED));

        assertThat(hub.open("one", 2).status()).isEqualTo(RunEventHub.OpenStatus.AHEAD);
        RunEventHub.OpenResult one = hub.open("one", 0);
        assertThat(one.status()).isEqualTo(RunEventHub.OpenStatus.OPEN);
        assertThat(hub.open("one", 0).status()).isEqualTo(RunEventHub.OpenStatus.CAPACITY);
        RunEventHub.OpenResult two = hub.open("two", 0);
        assertThat(two.status()).isEqualTo(RunEventHub.OpenStatus.OPEN);
        assertThat(hub.subscriptionCount()).isEqualTo(2);
        one.subscription().close();
        two.subscription().close();
        assertThat(hub.subscriptionCount()).isZero();
    }

    private static InMemoryRunEventHub hub(int perRun, int global) {
        return new InMemoryRunEventHub(new ObjectMapper(), 256, 1_048_576, 16_384,
                4, 8, Duration.ofMinutes(10).toNanos(), System::nanoTime, perRun, global);
    }

    private static RunEvent.Draft event(String taskId, RunEvent.Type type) {
        return new RunEvent.Draft(taskId, taskId, type, Instant.parse("2026-09-15T00:00:00Z"),
                Map.of("status", type.name()));
    }
}
