package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunResourceLimitTest {
    @Test
    void thirtyTwoGlobalSubscriptionsAreHardBoundedAndAllPermitsReturn() {
        InMemoryRunEventHub hub = new InMemoryRunEventHub(new ObjectMapper(), 256, 1_048_576, 16_384,
                36, 128, Duration.ofMinutes(10).toNanos(), System::nanoTime, 16, 32);
        List<RunEventHub.Subscription> opened = new ArrayList<>();
        for (int i = 0; i < 33; i++) hub.create("task-" + i);
        for (int i = 0; i < 32; i++) {
            RunEventHub.OpenResult result = hub.open("task-" + i, 0);
            assertThat(result.status()).isEqualTo(RunEventHub.OpenStatus.OPEN);
            opened.add(result.subscription());
        }
        assertThat(hub.open("task-32", 0).status()).isEqualTo(RunEventHub.OpenStatus.CAPACITY);
        assertThat(hub.subscriptionCount()).isEqualTo(32);
        opened.forEach(RunEventHub.Subscription::close);
        assertThat(hub.subscriptionCount()).isZero();
    }
}
