package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunCompletionTest {
    @Test
    void commitsReturnedResultBeforePublishingOneTerminalEventAndReleasesCapacity() throws Exception {
        Instant now=Instant.parse("2026-09-15T00:00:00Z"); RunPersistence p=mock(RunPersistence.class);
        when(p.createQueued(any())).thenAnswer(i->RunStartTest.queued(i.getArgument(0),now));
        when(p.markRunning(anyString(),any())).thenReturn(new RunPersistence.StartResult(RunPersistence.StartOutcome.STARTED,RunStartTest.running("task",now)));
        when(p.complete(any())).thenAnswer(i->new RunPersistence.CommittedTerminal(RunStartTest.success(i.getArgument(0),now),true,false));
        RunCoordinator c=RunStartTest.coordinator((r,s,o)->AgentResult.success(r.taskId(),"done",List.of(),TokenUsage.empty()),p,now);
        try {
            c.create("owner","hello");
            verify(p,timeout(2000).times(1)).complete(any());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(c.inFlightCount()!=0 && System.nanoTime()<end) Thread.onSpinWait();
            assertThat(c.inFlightCount()).isZero();
            assertThat(c.control("task")).isEmpty();
        } finally { c.close(); }
    }

    @Test
    void convertsThrownRuntimeFailureToSafeInternalTerminal() {
        Instant now=Instant.parse("2026-09-15T00:00:00Z"); RunPersistence p=mock(RunPersistence.class);
        when(p.createQueued(any())).thenAnswer(i->RunStartTest.queued(i.getArgument(0),now));
        when(p.markRunning(anyString(),any())).thenReturn(new RunPersistence.StartResult(RunPersistence.StartOutcome.STARTED,RunStartTest.running("task",now)));
        when(p.complete(any())).thenAnswer(i->{ RunResultProjector.FinalProjection f=i.getArgument(0);
            assertThat(f.errorCode()).isEqualTo("INTERNAL_ERROR"); return new RunPersistence.CommittedTerminal(
                    new RunSnapshot("task","task","session",RunLifecycleStatus.FAILED,"hello",null,now,now,now,now,false,RunTerminationReason.INTERNAL_ERROR,null,"INTERNAL_ERROR",false,null),true,false); });
        RunCoordinator c=RunStartTest.coordinator((r,s,o)->{throw new IllegalStateException("Bearer secret");},p,now);
        try { c.create("owner","hello"); verify(p,timeout(2000)).complete(any()); } finally { c.close(); }
    }
}
