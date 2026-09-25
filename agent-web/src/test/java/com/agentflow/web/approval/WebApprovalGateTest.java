package com.agentflow.web.approval;

import com.agentflow.core.approval.*;
import com.agentflow.core.runtime.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebApprovalGateTest {
    @Test void waitsOnCallingWorkerThenUsesSameBoundRequestForSingleDispatch() {
        var service = mock(ApprovalService.class);
        var request = ApprovalFixtures.request();
        var run = ApprovalFixtures.control();
        var budget = new ToolExecutionControl(run, TimeSource.system(), Duration.ofSeconds(1));
        var row = ToolInvocationEntity.pending(request, "owner");
        when(service.poll("owner", ApprovalFixtures.RUN, request.approvalId().toString())).thenAnswer(i -> {
            row.resolve(ApprovalStatus.APPROVED, ApprovalResolution.DecisionSource.USER, ApprovalFixtures.NOW, 5);
            return row.snapshot();
        });
        when(service.dispatch(request, budget)).thenReturn(true);
        try (var gate = new WebApprovalGate(service, run, event -> {})) {
            assertThat(gate.await(request, budget).status()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(gate.claimDispatch(request, budget)).isTrue();
            assertThat(gate.claimDispatch(request, budget)).isFalse();
        }
        verify(service, times(1)).dispatch(request, budget);
        verify(service).release(request.approvalId().toString());
    }

    @Test void cancellationAfterApprovalPreventsDispatch() {
        var service = mock(ApprovalService.class);
        var request = ApprovalFixtures.request();
        var run = ApprovalFixtures.control();
        var budget = new ToolExecutionControl(run, TimeSource.system(), Duration.ofSeconds(1));
        var row = ToolInvocationEntity.pending(request, "owner");
        row.resolve(ApprovalStatus.APPROVED, ApprovalResolution.DecisionSource.USER, ApprovalFixtures.NOW, 0);
        when(service.poll(anyString(), anyString(), anyString())).thenReturn(row.snapshot());
        try (var gate = new WebApprovalGate(service, run, event -> {})) {
            gate.await(request, budget); run.requestCancel();
            assertThat(gate.claimDispatch(request, budget)).isFalse();
        }
        verify(service, never()).dispatch(any(), any());
    }
}
