package com.agentflow.web.approval;

import com.agentflow.core.approval.*;
import com.agentflow.core.runtime.*;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalServiceTest {
    @Test void creationCommitsBeforeEventAndUserDecisionIsIdempotent() {
        var request = ApprovalFixtures.request();
        var persistence = mock(ApprovalPersistence.class);
        var row = ToolInvocationEntity.pending(request, "owner");
        when(persistence.create(request)).thenReturn(row.snapshot());
        when(persistence.getOwned("owner", ApprovalFixtures.RUN, request.approvalId().toString())).thenAnswer(i -> row.snapshot());
        var control = ApprovalFixtures.control();
        var service = new ApprovalService(persistence, Clock.fixed(ApprovalFixtures.NOW, ZoneOffset.UTC), System::nanoTime);
        var events = new java.util.ArrayList<RunEvent.Draft>();
        service.begin(request, control, new ToolExecutionControl(control, TimeSource.system(), Duration.ofSeconds(30)), events::add);
        assertThat(events).hasSize(1);
        when(persistence.resolve(anyString(), anyString(), anyString(), any(), any(), any(), anyLong()))
                .thenAnswer(i -> { row.resolve(i.getArgument(3), i.getArgument(4), i.getArgument(5), i.getArgument(6)); return row.snapshot(); });
        var first = service.decide("owner", ApprovalFixtures.RUN, request.approvalId().toString(), ApprovalService.Decision.APPROVE);
        var again = service.decide("owner", ApprovalFixtures.RUN, request.approvalId().toString(), ApprovalService.Decision.APPROVE);
        assertThat(first.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(again).isEqualTo(first);
        assertThat(events).extracting(RunEvent.Draft::type).containsExactly(RunEvent.Type.APPROVAL_REQUESTED, RunEvent.Type.APPROVAL_RESOLVED);
        assertThatThrownBy(() -> service.decide("owner", ApprovalFixtures.RUN, request.approvalId().toString(), ApprovalService.Decision.REJECT))
                .isInstanceOf(ApprovalService.ConflictException.class);
        verify(persistence, times(1)).resolve(anyString(), anyString(), anyString(), any(), any(), any(), anyLong());
    }

    @Test void oppositeConcurrentDecisionsCommitExactlyOneWinner() throws Exception {
        var request = ApprovalFixtures.request();
        var persistence = mock(ApprovalPersistence.class);
        var row = ToolInvocationEntity.pending(request, "owner");
        when(persistence.create(request)).thenAnswer(i -> row.snapshot());
        when(persistence.getOwned(anyString(), anyString(), anyString())).thenAnswer(i -> { synchronized (row) { return row.snapshot(); } });
        when(persistence.resolve(anyString(), anyString(), anyString(), any(), any(), any(), anyLong())).thenAnswer(i -> {
            synchronized (row) { row.resolve(i.getArgument(3), i.getArgument(4), i.getArgument(5), i.getArgument(6)); return row.snapshot(); }
        });
        var run = ApprovalFixtures.control();
        var service = new ApprovalService(persistence, Clock.fixed(ApprovalFixtures.NOW, ZoneOffset.UTC), System::nanoTime);
        var events = new java.util.concurrent.CopyOnWriteArrayList<RunEvent.Draft>();
        service.begin(request, run, new ToolExecutionControl(run, TimeSource.system(), Duration.ofSeconds(30)), events::add);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (var decision : ApprovalService.Decision.values()) results.add(pool.submit(() -> {
                start.await();
                try { return service.decide("owner", ApprovalFixtures.RUN, request.approvalId().toString(), decision).status().name(); }
                catch (ApprovalService.ConflictException conflict) { return conflict.code(); }
            }));
            start.countDown();
            var values = java.util.List.of(results.get(0).get(3, java.util.concurrent.TimeUnit.SECONDS), results.get(1).get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(values).contains("APPROVAL_ALREADY_DECIDED");
            assertThat(values.stream().filter(v -> v.equals("APPROVED") || v.equals("REJECTED"))).hasSize(1);
            assertThat(events).hasSize(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void stalePendingReadReturnsCommittedDecisionEvenAfterWorkerFinalizes() {
        var request = ApprovalFixtures.request();
        var persistence = mock(ApprovalPersistence.class);
        var row = ToolInvocationEntity.pending(request, "owner");
        var pending = row.snapshot();
        when(persistence.create(request)).thenReturn(pending);
        var run = ApprovalFixtures.control();
        var service = new ApprovalService(persistence, Clock.fixed(ApprovalFixtures.NOW, ZoneOffset.UTC), System::nanoTime);
        service.begin(request, run, new ToolExecutionControl(run, TimeSource.system(), Duration.ofSeconds(30)), event -> {});
        row.resolve(ApprovalStatus.APPROVED, ApprovalResolution.DecisionSource.USER, ApprovalFixtures.NOW, 1);
        run.freezeFinal(new Object());
        when(persistence.getOwned("owner", ApprovalFixtures.RUN, request.approvalId().toString()))
                .thenReturn(pending, row.snapshot());
        assertThat(service.decide("owner", ApprovalFixtures.RUN, request.approvalId().toString(), ApprovalService.Decision.APPROVE))
                .isEqualTo(row.snapshot());
        verify(persistence, never()).resolve(anyString(), anyString(), anyString(), any(), any(), any(), anyLong());
        verify(persistence, never()).dispatch(any(), any());
    }

    @Test void sharedClaimSerializesCancellationAndBlocksDispatchAfterCancellation() {
        var control = ApprovalFixtures.control();
        long claim = control.claimApprovalIo().orElseThrow();
        assertThat(control.claimCancellationWrite()).isEmpty();
        control.requestCancel();
        assertThat(control.finishApprovalIo(claim, true)).isFalse();
        assertThat(control.claimCancellationWrite()).isPresent();
    }
}
