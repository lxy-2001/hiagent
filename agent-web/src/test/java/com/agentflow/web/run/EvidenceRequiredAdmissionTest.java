package com.agentflow.web.run;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EvidenceRequiredAdmissionTest {
    @Test void admissionAndContextPreparationPreserveEvidencePolicy() throws Exception {
        var now = Instant.parse("2026-09-22T00:00:00Z");
        var persistence = mock(RunPersistence.class);
        when(persistence.createQueued(any())).thenAnswer(i -> RunStartTest.queued(i.getArgument(0), now));
        when(persistence.markRunning(anyString(), any())).thenReturn(new RunPersistence.StartResult(
                RunPersistence.StartOutcome.STARTED, RunStartTest.running("task", now)));
        when(persistence.complete(any())).thenAnswer(i -> new RunPersistence.CommittedTerminal(
                RunStartTest.success(i.getArgument(0), now), true, false));
        var received = new AtomicReference<AgentRequest>();
        var called = new CountDownLatch(1);
        var coordinator = RunStartTest.coordinator((request, sink, options) -> {
            received.set(request); called.countDown();
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        }, persistence, now);
        try {
            coordinator.create("owner", "question", null, true);
            assertThat(called.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get().requireEvidence()).isTrue();
        } finally { coordinator.close(); }
    }
}
