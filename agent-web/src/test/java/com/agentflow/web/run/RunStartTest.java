package com.agentflow.web.run;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.AgentRunOptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunStartTest {
    @Test
    void committedStartPrecedesTheOnlyThreeArgumentRuntimeCall() throws Exception {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        RunPersistence p = mock(RunPersistence.class);
        when(p.createQueued(any())).thenAnswer(i -> queued(i.getArgument(0), now));
        when(p.markRunning(anyString(), any())).thenAnswer(i -> new RunPersistence.StartResult(
                RunPersistence.StartOutcome.STARTED, running(i.getArgument(0), now)));
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<AgentRequest> request = new AtomicReference<>();
        AtomicReference<AgentRunOptions> options = new AtomicReference<>();
        AgentRuntime runtime = (r, sink, o) -> { request.set(r); options.set(o); called.countDown();
            return AgentResult.success(r.taskId(), "done", List.of(), TokenUsage.empty()); };
        when(p.complete(any())).thenAnswer(i -> new RunPersistence.CommittedTerminal(
                success(i.<RunResultProjector.FinalProjection>getArgument(0), now), true, false));
        RunCoordinator c = coordinator(runtime, p, now);
        try {
            c.create("owner", "hello");
            assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();
            verify(p, timeout(2000)).markRunning(eq("task"), eq(now));
            assertThat(request.get()).isEqualTo(new AgentRequest("task", "session", "owner", "hello"));
            assertThat(options.get().budget().maxIterations()).isEqualTo(8);
            assertThat(options.get().cancellationSignal()).isInstanceOf(RunControl.class);
        } finally { c.close(); }
    }

    static RunCoordinator coordinator(AgentRuntime runtime, RunPersistence p, Instant now) {
        RunLifecycleProperties limits = new RunLifecycleProperties(1,1,2,Duration.ofSeconds(30),256,
                1_048_576,16_384,128,Duration.ofMinutes(10),16,32,32);
        InMemoryRunEventHub hub = new InMemoryRunEventHub(new ObjectMapper(),256,1_048_576,16_384,
                2,128,Duration.ofMinutes(10).toNanos(),System::nanoTime);
        AtomicInteger ids = new AtomicInteger();
        return new RunCoordinator(runtime,p,hub,new RunEventProjector(),new RunResultProjector(),
                new BoundedRunExecutor(1,1,Thread::new),limits,Clock.fixed(now, ZoneOffset.UTC),
                System::nanoTime,()->ids.getAndIncrement()==0?"task":"session");
    }
    static RunSnapshot queued(RunPersistence.CreateCommand c, Instant now) { return new RunSnapshot(c.taskId(),c.taskId(),c.sessionId(),RunLifecycleStatus.QUEUED,c.input(),null,now,now,null,null,false,null,null,null,false,null); }
    static RunSnapshot running(String task, Instant now) { return new RunSnapshot(task,task,"session",RunLifecycleStatus.RUNNING,"hello",null,now,now,now,null,false,null,null,null,false,null); }
    static RunSnapshot success(RunResultProjector.FinalProjection f, Instant now) { return new RunSnapshot(f.taskId(),f.taskId(),"session",RunLifecycleStatus.SUCCEEDED,"hello","done",now,now,now,now,false,RunTerminationReason.COMPLETED,com.agentflow.core.runtime.TerminationReason.COMPLETED,null,false,TokenUsage.empty()); }
}
