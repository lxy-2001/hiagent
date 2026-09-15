package com.agentflow.web.run;

import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.AgentRunOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockingRuntimeFixtureTest {

    private static final Duration WAIT = Duration.ofSeconds(2);

    @Test
    void blocksUntilReleasedAndTracksCallLifecycle() throws Exception {
        AgentResult expected = AgentResult.success("run-1", "done", List.of(), TokenUsage.empty());
        BlockingRuntimeFixture runtime = BlockingRuntimeFixture.returning(expected);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AgentResult> future = executor.submit(() -> runtime.run(request(), AgentEventSink.NOOP,
                    AgentRunOptions.defaults()));

            assertThat(runtime.awaitStarted(WAIT)).isTrue();
            assertThat(future.isDone()).isFalse();
            assertThat(runtime.callCount()).isEqualTo(1);
            assertThat(runtime.activeCount()).isEqualTo(1);
            assertThat(runtime.maxConcurrentCount()).isEqualTo(1);

            runtime.release();

            assertThat(future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isSameAs(expected);
            assertThat(runtime.awaitReturned(WAIT)).isTrue();
            assertThat(runtime.activeCount()).isZero();
        } finally {
            runtime.release();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    @Test
    void releasesActiveCountWhenConfiguredFailureIsThrown() throws Exception {
        IllegalStateException failure = new IllegalStateException("controlled failure");
        BlockingRuntimeFixture runtime = BlockingRuntimeFixture.throwing(failure);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AgentResult> future = executor.submit(() -> runtime.run(request(), AgentEventSink.NOOP,
                    AgentRunOptions.defaults()));
            assertThat(runtime.awaitStarted(WAIT)).isTrue();

            runtime.release();

            assertThatThrownBy(() -> future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(failure);
            assertThat(runtime.awaitReturned(WAIT)).isTrue();
            assertThat(runtime.activeCount()).isZero();
        } finally {
            runtime.release();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    private AgentRequest request() {
        return new AgentRequest("run-1", "session-1", "owner-1", "hello");
    }
}
