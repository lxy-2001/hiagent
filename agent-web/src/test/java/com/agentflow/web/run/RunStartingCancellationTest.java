package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunStartingCancellationTest {
    @Test
    void cancellationDuringStartReturnsUncertaintyThenCancelsWithoutCallingRuntime() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var support = new RunCancellationTestSupport((request, sink, options) -> {
            calls.incrementAndGet();
            throw new AssertionError("Cancelled start must not invoke Runtime");
        })) {
            var original = org.mockito.Mockito.mockingDetails(support.persistence).getStubbings().stream()
                    .filter(s -> s.getInvocation().getMethod().getName().equals("markRunning")).findFirst().orElseThrow();
            doAnswer(invocation -> {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("start barrier timed out");
                return original.answer(invocation);
            }).when(support.persistence).markRunning(anyString(), any());
            support.coordinator.create("owner", "input");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> support.coordinator.cancel("owner", "task-1"))
                        .isInstanceOf(RunCoordinator.RunUnavailableException.class);
            } finally { release.countDown(); }
            var terminal = RunRunningCancellationTest.awaitTerminal(support, java.time.Duration.ofSeconds(3));
            assertThat(terminal.status()).isEqualTo(RunLifecycleStatus.CANCELLED);
            assertThat(terminal.cancelRequested()).isTrue();
            assertThat(calls.get()).isZero();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (support.coordinator.inFlightCount() != 0 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(support.coordinator.inFlightCount()).isZero();
            support.coordinator.maintainOnce();
            assertThat(support.coordinator.availability()).isEqualTo(RunCoordinator.Availability.READY);
        } finally { release.countDown(); }
    }
}

