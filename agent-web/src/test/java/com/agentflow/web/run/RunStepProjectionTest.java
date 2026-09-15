package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.AgentStepStatus;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.RunStatus;
import com.agentflow.core.runtime.TerminationReason;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunStepProjectionTest {

    private static final String TASK_ID = "run-steps";
    private static final Instant FINISHED = Instant.parse("2026-09-15T09:00:00Z");
    private final RunResultProjector projector = new RunResultProjector();

    @Test
    void marksAContinuousSuccessfulTraceCompleteOnlyWithMatchingTermination() {
        AgentStepRecord decision = step(TASK_ID, 1, AgentStepType.MODEL_DECISION,
                AgentStepStatus.SUCCESS, false, null);
        AgentStepRecord termination = step(TASK_ID, 2, AgentStepType.TERMINATION,
                AgentStepStatus.SUCCESS, true, null);
        AgentResult result = AgentResult.success(TASK_ID, "done", List.of(decision, termination),
                new TokenUsage(3, 2, 5));

        RunResultProjector.FinalProjection projection = project(result, true);

        assertThat(projection.recordingComplete()).isTrue();
        assertThat(projection.steps()).extracting(RunResultProjector.ProjectedStep::stepNo)
                .containsExactly(1, 2);
        assertThat(projection.steps().get(1).terminal()).isTrue();
    }

    @Test
    void marksEmptyDuplicateCrossRunAndMismatchedTerminationTracesIncomplete() {
        AgentResult empty = AgentResult.success(TASK_ID, "done", List.of(), TokenUsage.empty());
        AgentResult duplicate = AgentResult.success(TASK_ID, "done", List.of(
                step(TASK_ID, 1, AgentStepType.MODEL_DECISION, AgentStepStatus.SUCCESS, false, null),
                step(TASK_ID, 1, AgentStepType.TERMINATION, AgentStepStatus.SUCCESS, true, null)),
                TokenUsage.empty());
        AgentResult crossRun = AgentResult.success(TASK_ID, "done", List.of(
                step("another-run", 1, AgentStepType.TERMINATION, AgentStepStatus.SUCCESS, true, null)),
                TokenUsage.empty());
        AgentResult mismatched = AgentResult.failure(TASK_ID, RunStatus.FAILED,
                TerminationReason.MODEL_ERROR, "failed", List.of(
                        step(TASK_ID, 1, AgentStepType.TERMINATION,
                                AgentStepStatus.FAILED, true, "TOOL_ERROR")), TokenUsage.empty());

        assertThat(project(empty, true).recordingComplete()).isFalse();
        assertThat(project(duplicate, true).recordingComplete()).isFalse();
        assertThat(project(crossRun, true).recordingComplete()).isFalse();
        assertThat(project(mismatched, true).recordingComplete()).isFalse();
    }

    @Test
    void capsOversizedTraceAt256WithoutClaimingTheTruncatedTraceIsComplete() {
        List<AgentStepRecord> steps = new ArrayList<>();
        for (int stepNo = 1; stepNo <= 256; stepNo++) {
            steps.add(step(TASK_ID, stepNo, AgentStepType.MODEL_DECISION,
                    AgentStepStatus.SUCCESS, false, null));
        }
        steps.add(step(TASK_ID, 257, AgentStepType.TERMINATION,
                AgentStepStatus.SUCCESS, true, null));

        RunResultProjector.FinalProjection projection = project(
                AgentResult.success(TASK_ID, "done", steps, TokenUsage.empty()), true);

        assertThat(projection.steps()).hasSize(256);
        assertThat(projection.recordingComplete()).isFalse();
    }

    @Test
    void redactsBeforeUnicodeSafeTruncationAndCapsNamesAndIds() {
        AgentStepRecord source = new AgentStepRecord(TASK_ID, 1, AgentStepType.TERMINATION,
                "n".repeat(101), "token=x" + "i".repeat(1_020) + "😀tail",
                "o".repeat(1_023) + "😀", AgentStepStatus.SUCCESS, 9,
                1, 2, "password=z" + "e".repeat(1_020) + "😀",
                "d".repeat(129), "c".repeat(129), null, true);
        AgentResult result = AgentResult.success(TASK_ID, "done", List.of(source), TokenUsage.empty());

        RunResultProjector.ProjectedStep step = project(result, true).steps().get(0);

        assertThat(step.name()).hasSize(100);
        assertThat(step.decisionId()).hasSize(128);
        assertThat(step.callId()).hasSize(128);
        assertThat(step.input()).doesNotContain("token=x").hasSizeLessThanOrEqualTo(1_024);
        assertThat(step.errorMessage()).doesNotContain("password=z").hasSizeLessThanOrEqualTo(1_024);
        assertThat(step.output()).hasSizeLessThanOrEqualTo(1_024);
        assertThat(Character.isHighSurrogate(step.output().charAt(step.output().length() - 1))).isFalse();
    }

    @Test
    void keepsTheSerializedPendingProjectionAtOrBelowOneMiB() throws Exception {
        List<AgentStepRecord> steps = new ArrayList<>();
        for (int stepNo = 1; stepNo < 256; stepNo++) {
            steps.add(new AgentStepRecord(TASK_ID, stepNo, AgentStepType.TOOL_RESULT, "tool",
                    "中".repeat(1_024), "文".repeat(1_024), AgentStepStatus.SUCCESS, 1,
                    null, null, "错".repeat(1_024), "decision", "call", null, false));
        }
        steps.add(step(TASK_ID, 256, AgentStepType.TERMINATION,
                AgentStepStatus.SUCCESS, true, null));

        RunResultProjector.FinalProjection projection = project(
                AgentResult.success(TASK_ID, "done", steps, TokenUsage.empty()), true);

        assertThat(new ObjectMapper().writeValueAsBytes(projection).length)
                .isLessThanOrEqualTo(1_048_576);
        assertThat(projection.recordingComplete()).isFalse();
    }

    private RunResultProjector.FinalProjection project(AgentResult result, boolean observationsComplete) {
        return projector.project(TASK_ID, result, FINISHED, false, observationsComplete);
    }

    private static AgentStepRecord step(String taskId, int number, AgentStepType type,
                                        AgentStepStatus status, boolean terminal, String errorCode) {
        return new AgentStepRecord(taskId, number, type, "tool", "input", "output", status, 1,
                1, 1, status == AgentStepStatus.FAILED ? "failed" : null,
                "decision-" + number, "call-" + number, errorCode, terminal);
    }
}
