package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RunSseCleanupTest {
    @Test
    void subscriptionPermitIsReturnedExactlyOnceAndEvictionWakesReaders() throws Exception {
        InMemoryRunEventHub hub = new InMemoryRunEventHub(new ObjectMapper(), 2, 1024, 512,
                1, 1, Duration.ofNanos(1).toNanos(), System::nanoTime, 1, 1);
        hub.create("task");
        RunEventHub.OpenResult opened = hub.open("task", 0);
        assertThat(hub.subscriptionCount()).isOne();
        opened.subscription().close();
        opened.subscription().close();
        assertThat(hub.subscriptionCount()).isZero();

        hub.markTerminal("task");
        hub.maintain();
        assertThat(opened.subscription().read(0, Duration.ZERO).status())
                .isEqualTo(RunEventHub.ReadStatus.UNAVAILABLE);
        assertThat(hub.cachedRunCount()).isZero();
    }
}
