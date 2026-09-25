package com.agentflow.web.approval;

import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ApprovalPersistenceTest {
    private static final String RUN = UUID.randomUUID().toString();
    private static final String HASH = "a".repeat(64);
    private static final Instant CREATED = Instant.parse("2026-09-25T00:00:00Z");

    static ToolInvocationRecord record(String run) {
        return new ToolInvocationRecord("006-v1", run, "call-1", "local", null, null,
                HASH, HASH, HASH, RiskLevel.LOW, ToolPolicyDecision.Effect.READ_ONLY,
                ToolPolicyDecision.Action.ALLOW, CREATED, CREATED.plusMillis(3),
                null, null, null, 1, 2L, ToolInvocationRecord.Outcome.SUCCEEDED, null);
    }

    @Test void ordinaryInvocationPreservesFactsWithoutInventingApproval() {
        ToolInvocationEntity row = ToolInvocationEntity.completed(RUN, "owner", record(RUN));
        assertThat(row.getTaskId()).isEqualTo(RUN);
        assertThat(row.getOwnerId()).isEqualTo("owner");
        assertThat(row.getArgumentsDigest()).isEqualTo(HASH);
        assertThat(row.getCreatedAt()).isEqualTo(CREATED);
        assertThat(row.getDispatchAt()).isEqualTo(CREATED.plusMillis(3));
        assertThat(row.getApprovalStatus()).isNull();
        assertThat(row.getActionSummary()).isNull();
        assertThat(row.getArgumentPreviewJson()).isNull();
        assertThat(row.getExpiresAt()).isNull();
        assertThat(row.getDecisionSource()).isNull();
    }

    @Test void projectionCarriesTypedFactsWithoutReconstructingPolicy() {
        var fact = record(RUN);
        var projection = new com.agentflow.web.run.RunResultProjector.FinalProjection(RUN,
                com.agentflow.web.run.RunLifecycleStatus.SUCCEEDED,
                com.agentflow.web.run.RunTerminationReason.COMPLETED,
                com.agentflow.core.runtime.TerminationReason.COMPLETED, "done",
                com.agentflow.core.chat.TokenUsage.empty(), CREATED.plusSeconds(1), false, true,
                null, java.util.List.of(), java.util.List.of(), java.util.List.of(fact));
        assertThat(projection.toolInvocations()).containsExactly(fact);
        assertThatThrownBy(() -> new com.agentflow.web.run.RunResultProjector.FinalProjection(RUN,
                projection.status(), projection.terminationReason(), projection.runtimeReason(), "done",
                projection.usage(), projection.finishedAt(), false, true, null, java.util.List.of(),
                java.util.List.of(), java.util.List.of(record(UUID.randomUUID().toString()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsWrongRunAndNonUuidWebIdentity() {
        assertThatThrownBy(() -> ToolInvocationEntity.completed(RUN, "owner", record(UUID.randomUUID().toString())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolInvocationEntity.completed("demo-task", "owner", record("demo-task")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
