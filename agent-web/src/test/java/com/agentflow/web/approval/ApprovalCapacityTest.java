package com.agentflow.web.approval;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.context.ContextSeed;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalCapacityTest {
    @Test void fourWaitingWorkersKeepSlotsAndSessionsUntilExitAndCommit() throws Exception {
        var persistence = mock(RunPersistence.class);
        var commands = new ConcurrentHashMap<String, RunPersistence.CreateCommand>();
        when(persistence.ownsSession(anyString(), anyString())).thenReturn(true);
        when(persistence.createQueued(any())).thenAnswer(i -> {
            RunPersistence.CreateCommand c = i.getArgument(0); commands.put(c.taskId(), c);
            return new RunSnapshot(c.taskId(),c.taskId(),c.sessionId(),RunLifecycleStatus.QUEUED,c.input(),null,c.createdAt(),c.createdAt(),null,null,false,null,null,null,false,null);
        });
        when(persistence.markRunning(anyString(), any())).thenAnswer(i -> {
            var c=commands.get(i.getArgument(0));
            return new RunPersistence.StartResult(RunPersistence.StartOutcome.STARTED,
                new RunSnapshot(c.taskId(),c.taskId(),c.sessionId(),RunLifecycleStatus.RUNNING,c.input(),null,c.createdAt(),c.createdAt(),c.createdAt(),null,false,null,null,null,false,null));
        });
        when(persistence.complete(any())).thenThrow(new IllegalStateException("storage unavailable"));
        var entered = new CountDownLatch(4); var leave = new CountDownLatch(1);
        AgentRuntime runtime = (request, sink, options) -> {
            entered.countDown();
            try { if (!leave.await(3, TimeUnit.SECONDS)) throw new AssertionError("worker not released"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        };
        var limits = RunLifecycleProperties.defaults();
        var hub = new InMemoryRunEventHub(new ObjectMapper(),256,1048576,16384,36,128,
                Duration.ofMinutes(10).toNanos(),System::nanoTime,16,32);
        var coordinator = new RunCoordinator((q,t,c) -> ContextSeed.empty(), runtime, persistence, hub,
                new RunEventProjector(),new RunResultProjector(),new BoundedRunExecutor(4,32,Thread::new),limits,
                Clock.systemUTC(),System::nanoTime,() -> UUID.randomUUID().toString());
        try {
            for (int i=0;i<4;i++) coordinator.create("owner", "hello", "session-"+i);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            coordinator.create("owner", "hello", "session-4");
            assertThat(coordinator.inFlightCount()).isEqualTo(5);
            assertThatThrownBy(() -> coordinator.create("owner", "hello", "session-0")).isInstanceOf(RunCoordinator.SessionBusyException.class);
            leave.countDown();
            verify(persistence, timeout(2000).atLeastOnce()).complete(any());
            assertThat(coordinator.inFlightCount()).isEqualTo(5);
        } finally { leave.countDown(); coordinator.close(); }
    }
}
