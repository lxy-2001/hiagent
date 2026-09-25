package com.agentflow.web.approval;

import com.agentflow.core.approval.*;
import com.agentflow.web.agent.*;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = ApprovalRunIntegrationTest.App.class)
class ApprovalRestartTest {
    @Autowired RunPersistence runs;
    @Autowired ApprovalPersistence approvals;
    @Autowired AgentTaskRepository tasks;
    @Autowired AgentSessionRepository sessions;
    @Autowired ToolInvocationRepository invocations;
    @org.junit.jupiter.api.BeforeEach void clean() { invocations.deleteAll(); tasks.deleteAll(); sessions.deleteAll(); }

    @Test void waitingProcessIsInterruptedWithoutRecreatingCommands() {
        var request = start();
        var results = runs.convergeInterrupted("", 100, ApprovalFixtures.NOW.plusSeconds(3));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).terminationReason()).isEqualTo(RunTerminationReason.PROCESS_INTERRUPTED);
        var approval = approvals.getOwned("owner", ApprovalFixtures.RUN, request.approvalId().toString());
        assertThat(approval.status()).isEqualTo(ApprovalStatus.INTERRUPTED);
        assertThat(approval.dispatchCount()).isZero();
        assertThat(approval.waitMillis()).isNull();
        assertThat(runs.convergeInterrupted("", 100, ApprovalFixtures.NOW.plusSeconds(4))).isEmpty();
    }

    @Test void approvedUnknownDispatchRemainsApprovedAndUnknownAfterRestart() {
        var request = start();
        approvals.resolve("owner", ApprovalFixtures.RUN, request.approvalId().toString(), ApprovalStatus.APPROVED,
                ApprovalResolution.DecisionSource.USER, ApprovalFixtures.NOW, 1);
        approvals.dispatch(request, ApprovalFixtures.NOW.plusMillis(2));
        runs.convergeInterrupted("", 100, ApprovalFixtures.NOW.plusSeconds(3));
        var approval = approvals.getOwned("owner", ApprovalFixtures.RUN, request.approvalId().toString());
        assertThat(approval.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.outcome().name()).isEqualTo("UNKNOWN");
        assertThat(approval.dispatchCount()).isEqualTo(1);
        assertThat(tasks.findById(ApprovalFixtures.RUN).orElseThrow().isRecordingComplete()).isFalse();
    }
    private ApprovalRequest start() {
        runs.createQueued(new RunPersistence.CreateCommand(ApprovalFixtures.RUN, "s", "owner", "hello", "hello", ApprovalFixtures.NOW));
        runs.markRunning(ApprovalFixtures.RUN, ApprovalFixtures.NOW);
        var request = ApprovalFixtures.request(); approvals.create(request); return request;
    }
}
