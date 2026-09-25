package com.agentflow.web.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLifecyclePropertiesTest {

    @Test
    void approvalBudgetUsesBoundedApplicationDuration() {
        assertThat(RunLifecycleProperties.defaults().maxDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(RunLifecycleProperties.defaults().withMaxDuration(Duration.ofSeconds(120)).maxDuration()).isEqualTo(Duration.ofSeconds(120));
        for (Duration invalid : java.util.List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(121)))
            assertThatThrownBy(() -> RunLifecycleProperties.defaults().withMaxDuration(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesTheFixedProductionLimitsAsDefaults() {
        RunLifecycleProperties properties = RunLifecycleProperties.defaults();

        assertThat(properties.workerThreads()).isEqualTo(4);
        assertThat(properties.queueCapacity()).isEqualTo(32);
        assertThat(properties.inFlightCapacity()).isEqualTo(36);
        assertThat(properties.queueTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.eventWindowCount()).isEqualTo(256);
        assertThat(properties.eventWindowBytes()).isEqualTo(1024 * 1024);
        assertThat(properties.eventFrameBytes()).isEqualTo(16 * 1024);
        assertThat(properties.terminalCacheCapacity()).isEqualTo(128);
        assertThat(properties.terminalCacheTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.subscriptionsPerRun()).isEqualTo(16);
        assertThat(properties.globalSubscriptions()).isEqualTo(32);
        assertThat(properties.senderThreads()).isEqualTo(32);
    }

    @Test
    void permitsSmallerInternallyConsistentValuesForDeterministicTests() {
        RunLifecycleProperties properties = properties(1, 2, 3, Duration.ofSeconds(2),
                8, 8 * 1024, 1024, 4, Duration.ofMinutes(1), 2, 4, 4);

        assertThat(properties.inFlightCapacity()).isEqualTo(3);
        assertThat(properties.eventFrameBytes()).isLessThanOrEqualTo(properties.eventWindowBytes());
        assertThat(properties.globalSubscriptions()).isLessThanOrEqualTo(properties.senderThreads());
    }

    @Test
    void rejectsNonPositiveAndInconsistentCapacityValues() {
        assertThatThrownBy(() -> properties(0, 2, 2, Duration.ofSeconds(2),
                8, 8192, 1024, 4, Duration.ofMinutes(1), 2, 4, 4))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workerThreads");
        assertThatThrownBy(() -> properties(1, 2, 4, Duration.ofSeconds(2),
                8, 8192, 1024, 4, Duration.ofMinutes(1), 2, 4, 4))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inFlightCapacity");
        assertThatThrownBy(() -> properties(1, 2, 3, Duration.ZERO,
                8, 8192, 1024, 4, Duration.ofMinutes(1), 2, 4, 4))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("queueTimeout");
        assertThatThrownBy(() -> properties(1, 2, 3, Duration.ofSeconds(2),
                8, 1024, 2048, 4, Duration.ofMinutes(1), 2, 4, 4))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventFrameBytes");
        assertThatThrownBy(() -> properties(1, 2, 3, Duration.ofSeconds(2),
                8, 8192, 1024, 4, Duration.ofMinutes(1), 5, 4, 4))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subscriptionsPerRun");
    }

    @Test
    void rejectsEveryValueAboveItsProductionHardLimit() {
        assertInvalid(() -> properties(5, 32, 37, Duration.ofSeconds(30),
                256, 1_048_576, 16_384, 128, Duration.ofMinutes(10), 16, 32, 32), "workerThreads");
        assertInvalid(() -> properties(4, 33, 37, Duration.ofSeconds(30),
                256, 1_048_576, 16_384, 128, Duration.ofMinutes(10), 16, 32, 32), "queueCapacity");
        assertInvalid(() -> properties(4, 32, 36, Duration.ofSeconds(31),
                256, 1_048_576, 16_384, 128, Duration.ofMinutes(10), 16, 32, 32), "queueTimeout");
        assertInvalid(() -> properties(4, 32, 36, Duration.ofSeconds(30),
                257, 1_048_576, 16_384, 128, Duration.ofMinutes(10), 16, 32, 32), "eventWindowCount");
        assertInvalid(() -> properties(4, 32, 36, Duration.ofSeconds(30),
                256, 1_048_577, 16_384, 128, Duration.ofMinutes(10), 16, 32, 32), "eventWindowBytes");
        assertInvalid(() -> properties(4, 32, 36, Duration.ofSeconds(30),
                256, 1_048_576, 16_385, 128, Duration.ofMinutes(10), 16, 32, 32), "eventFrameBytes");
        assertInvalid(() -> properties(4, 32, 36, Duration.ofSeconds(30),
                256, 1_048_576, 16_384, 129, Duration.ofMinutes(10), 16, 32, 32), "terminalCacheCapacity");
        assertInvalid(() -> properties(4, 32, 36, Duration.ofSeconds(30),
                256, 1_048_576, 16_384, 128, Duration.ofMinutes(10).plusNanos(1), 16, 32, 32), "terminalCacheTtl");
        assertInvalid(() -> properties(4, 32, 36, Duration.ofSeconds(30),
                256, 1_048_576, 16_384, 128, Duration.ofMinutes(10), 17, 32, 32), "subscriptionsPerRun");
        assertInvalid(() -> properties(4, 32, 36, Duration.ofSeconds(30),
                256, 1_048_576, 16_384, 128, Duration.ofMinutes(10), 16, 33, 33), "globalSubscriptions");
        assertInvalid(() -> properties(4, 32, 36, Duration.ofSeconds(30),
                256, 1_048_576, 16_384, 128, Duration.ofMinutes(10), 16, 32, 33), "senderThreads");
    }

    private void assertInvalid(ThrowingCallable construction, String field) {
        assertThatThrownBy(construction)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field);
    }

    private RunLifecycleProperties properties(int workers, int queue, int inFlight, Duration queueTimeout,
                                              int windowCount, int windowBytes, int frameBytes,
                                              int terminalCapacity, Duration terminalTtl,
                                              int perRun, int global, int senders) {
        return new RunLifecycleProperties(workers, queue, inFlight, queueTimeout,
                windowCount, windowBytes, frameBytes, terminalCapacity, terminalTtl,
                perRun, global, senders);
    }
}
