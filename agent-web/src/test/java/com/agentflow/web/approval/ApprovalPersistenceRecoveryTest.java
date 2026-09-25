package com.agentflow.web.approval;

import com.agentflow.core.approval.*;
import com.agentflow.core.runtime.*;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalPersistenceRecoveryTest {
    @Test void createAndDecisionCommitThenThrowAreReadBackWithoutDuplicateWriteOrExecution() {
        var request = ApprovalFixtures.request(); var row = ToolInvocationEntity.pending(request, "owner");
        var persistence = mock(ApprovalPersistence.class);
        when(persistence.create(request)).thenThrow(new IllegalStateException("commit response lost"));
        when(persistence.getOwned("owner", ApprovalFixtures.RUN, request.approvalId().toString())).thenAnswer(i -> row.snapshot());
        when(persistence.resolve(anyString(), anyString(), anyString(), any(), any(), any(), anyLong())).thenAnswer(i -> {
            row.resolve(i.getArgument(3), i.getArgument(4), i.getArgument(5), i.getArgument(6));
            throw new IllegalStateException("commit response lost");
        });
        var service = new ApprovalService(persistence, Clock.fixed(ApprovalFixtures.NOW, ZoneOffset.UTC), System::nanoTime);
        var run = ApprovalFixtures.control();
        var events = new java.util.ArrayList<RunEvent.Draft>();
        service.begin(request, run, new ToolExecutionControl(run, TimeSource.system(), Duration.ofSeconds(10)), events::add);
        assertThat(service.decide("owner", ApprovalFixtures.RUN, request.approvalId().toString(), ApprovalService.Decision.APPROVE).status())
                .isEqualTo(ApprovalStatus.APPROVED);
        assertThat(events).hasSize(2);
        verify(persistence, times(1)).create(request);
        verify(persistence, times(1)).resolve(anyString(), anyString(), anyString(), any(), any(), any(), anyLong());
        verify(persistence, never()).dispatch(any(), any());
    }

    @Test void uncertainDispatchIsReadBackAndNotSentTwice() {
        var request = ApprovalFixtures.request(); var row = ToolInvocationEntity.pending(request, "owner");
        var persistence = mock(ApprovalPersistence.class);
        when(persistence.create(request)).thenAnswer(i -> row.snapshot());
        when(persistence.getOwned(anyString(), anyString(), anyString())).thenAnswer(i -> row.snapshot());
        var service = new ApprovalService(persistence, Clock.fixed(ApprovalFixtures.NOW, ZoneOffset.UTC), System::nanoTime);
        var run = ApprovalFixtures.control(); var budget = new ToolExecutionControl(run, TimeSource.system(), Duration.ofSeconds(10));
        service.begin(request, run, budget, event -> {});
        row.resolve(ApprovalStatus.APPROVED, ApprovalResolution.DecisionSource.USER, ApprovalFixtures.NOW, 0);
        when(persistence.dispatch(eq(request), any())).thenAnswer(i -> { row.markDispatch(ApprovalFixtures.NOW); throw new IllegalStateException("lost"); });
        assertThat(service.dispatch(request, budget)).isTrue();
        verify(persistence, times(1)).dispatch(eq(request), any());
    }
}
