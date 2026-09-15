package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.RunStatus;
import com.agentflow.core.runtime.TerminationReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunResultProjectionTest {

    private static final Instant FINISHED = Instant.parse("2026-09-15T08:00:05Z");
    private final RunResultProjector projector = new RunResultProjector();

    @Test
    void acceptsAnswerAtLimitAndUsesRuntimeTotalUsageWithoutSummingSteps() {
        String answer = "a".repeat(65_536);
        TokenUsage usage = new TokenUsage(7, 3, 10);
        AgentStepRecord step = AgentStepRecord.success("run-1", 1, AgentStepType.MODEL_DECISION,
                "model", "input", "output", 2, 100, 200, "decision-1", null, false);
        AgentResult result = AgentResult.success("run-1", answer, List.of(step), usage);

        RunResultProjector.FinalProjection projection =
                projector.project("run-1", result, FINISHED, false, true);

        assertThat(projection.taskId()).isEqualTo("run-1");
        assertThat(projection.status()).isEqualTo(RunLifecycleStatus.SUCCEEDED);
        assertThat(projection.terminationReason()).isEqualTo(RunTerminationReason.COMPLETED);
        assertThat(projection.runtimeReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(projection.finalAnswer()).hasSize(65_536);
        assertThat(projection.usage()).isSameAs(usage);
        assertThat(projection.finishedAt()).isEqualTo(FINISHED);
        assertThat(projection.errorCode()).isNull();
    }

    @Test
    void failsOversizedAnswerWithoutTruncatingItIntoSuccess() {
        AgentResult result = AgentResult.success("run-1", "a".repeat(65_537), List.of(),
                new TokenUsage(4, 5, 9));

        RunResultProjector.FinalProjection projection =
                projector.project("run-1", result, FINISHED, true, true);

        assertThat(projection.status()).isEqualTo(RunLifecycleStatus.FAILED);
        assertThat(projection.terminationReason()).isEqualTo(RunTerminationReason.OUTPUT_TOO_LARGE);
        assertThat(projection.runtimeReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(projection.finalAnswer()).isNull();
        assertThat(projection.usage()).isEqualTo(new TokenUsage(4, 5, 9));
        assertThat(projection.errorCode()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(projection.cancelRequested()).isTrue();
        assertThat(projection.recordingComplete()).isFalse();
    }

    @Test
    void rejectsNullAndWrongTaskResultsAsControlledInvalidRuntimeResults() {
        RunResultProjector.FinalProjection nullResult =
                projector.project("run-1", null, FINISHED, false, true);
        AgentResult wrongTask = AgentResult.success("other-run", "answer", List.of(), TokenUsage.empty());
        RunResultProjector.FinalProjection wrongTaskResult =
                projector.project("run-1", wrongTask, FINISHED, false, true);

        assertInvalidRuntimeResult(nullResult);
        assertInvalidRuntimeResult(wrongTaskResult);
    }

    @Test
    void preservesAValidCoreFailureStatusReasonAndUsage() {
        TokenUsage usage = new TokenUsage(11, 2, 13);
        AgentResult result = AgentResult.failure("run-1", RunStatus.BUDGET_EXCEEDED,
                TerminationReason.BUDGET_EXCEEDED, "secret diagnostic", List.of(), usage);

        RunResultProjector.FinalProjection projection =
                projector.project("run-1", result, FINISHED, false, false);

        assertThat(projection.status()).isEqualTo(RunLifecycleStatus.BUDGET_EXCEEDED);
        assertThat(projection.terminationReason()).isEqualTo(RunTerminationReason.BUDGET_EXCEEDED);
        assertThat(projection.runtimeReason()).isEqualTo(TerminationReason.BUDGET_EXCEEDED);
        assertThat(projection.usage()).isSameAs(usage);
        assertThat(projection.finalAnswer()).isNull();
        assertThat(projection.errorCode()).isEqualTo("BUDGET_EXCEEDED");
    }

    @Test
    void createsFailureFromControlledClassificationWithoutThrowableOrResultRetention() {
        RunResultProjector.FinalProjection projection = projector.projectFailure(
                "run-1", RunResultProjector.FailureKind.INTERNAL_ERROR, FINISHED, true);

        assertThat(projection.status()).isEqualTo(RunLifecycleStatus.FAILED);
        assertThat(projection.terminationReason()).isEqualTo(RunTerminationReason.INTERNAL_ERROR);
        assertThat(projection.runtimeReason()).isNull();
        assertThat(projection.usage()).isNull();
        assertThat(projection.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(projection.getClass().getRecordComponents())
                .noneMatch(component -> Throwable.class.isAssignableFrom(component.getType())
                        || AgentResult.class.isAssignableFrom(component.getType()));
    }

    private void assertInvalidRuntimeResult(RunResultProjector.FinalProjection projection) {
        assertThat(projection.status()).isEqualTo(RunLifecycleStatus.FAILED);
        assertThat(projection.terminationReason()).isEqualTo(RunTerminationReason.INVALID_RUNTIME_RESULT);
        assertThat(projection.runtimeReason()).isNull();
        assertThat(projection.usage()).isNull();
        assertThat(projection.finalAnswer()).isNull();
        assertThat(projection.errorCode()).isEqualTo("INVALID_RUNTIME_RESULT");
    }
}
