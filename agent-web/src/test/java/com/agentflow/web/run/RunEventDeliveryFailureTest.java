package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunEventDeliveryFailureTest {
    @Test void eventFailureDoesNotDowngradeCommittedRuntimeResult() {
        Instant now=Instant.parse("2026-09-15T00:00:00Z"); RunPersistence p=mock(RunPersistence.class);
        when(p.createQueued(any())).thenAnswer(i->RunStartTest.queued(i.getArgument(0),now)); when(p.markRunning(anyString(),any())).thenReturn(new RunPersistence.StartResult(RunPersistence.StartOutcome.STARTED,RunStartTest.running("task",now)));
        when(p.complete(any())).thenAnswer(i->new RunPersistence.CommittedTerminal(RunStartTest.success(i.getArgument(0),now),true,false));
        RunEventHub hub=mock(RunEventHub.class); when(hub.create(anyString())).thenReturn(true); when(hub.publish(anyString(),any())).thenReturn(new RunEventHub.PublishResult(RunEventHub.PublishStatus.UNAVAILABLE,null));
        RunLifecycleProperties limits=new RunLifecycleProperties(1,1,2,java.time.Duration.ofSeconds(30),256,1048576,16384,128,java.time.Duration.ofMinutes(10),16,32,32);
        RunCoordinator c=new RunCoordinator((query, timeout, cancellation) -> com.agentflow.core.context.ContextSeed.empty(), (r,s,o)->AgentResult.success(r.taskId(),"done",List.of(),TokenUsage.empty()),p,hub,new RunEventProjector(),new RunResultProjector(),new BoundedRunExecutor(1,1,Thread::new),limits,java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),System::nanoTime,new java.util.function.Supplier<>(){int n;public String get(){return n++==0?"task":"session";}});
        try { c.create("owner","hello"); verify(p,timeout(2000)).complete(argThat(f->f.status()==RunLifecycleStatus.SUCCEEDED)); } finally { c.close(); }
    }
}
