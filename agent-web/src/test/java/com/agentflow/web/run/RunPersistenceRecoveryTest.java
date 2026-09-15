package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunPersistenceRecoveryTest {
    @Test void retriesFrozenFinalWithoutCallingRuntimeAgain() throws Exception {
        Instant now=Instant.parse("2026-09-15T00:00:00Z"); RunPersistence p=mock(RunPersistence.class);
        when(p.createQueued(any())).thenAnswer(i->RunStartTest.queued(i.getArgument(0),now));
        when(p.markRunning(anyString(),any())).thenReturn(new RunPersistence.StartResult(RunPersistence.StartOutcome.STARTED,RunStartTest.running("task",now)));
        when(p.complete(any())).thenThrow(new IllegalStateException("db")).thenAnswer(i->new RunPersistence.CommittedTerminal(RunStartTest.success(i.getArgument(0),now),true,false));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        RunCoordinator c=RunStartTest.coordinator((r,s,o)->{calls.incrementAndGet();return AgentResult.success(r.taskId(),"done",List.of(), TokenUsage.empty());},p,now);
        try { c.create("owner","hello"); verify(p,timeout(2000)).complete(any()); assertThat(c.availability()).isEqualTo(RunCoordinator.Availability.DEGRADED);
            c.maintainOnce(); verify(p,times(2)).complete(any()); assertThat(calls).hasValue(1); assertThat(c.availability()).isEqualTo(RunCoordinator.Availability.READY);
        } finally { c.close(); }
    }
}
