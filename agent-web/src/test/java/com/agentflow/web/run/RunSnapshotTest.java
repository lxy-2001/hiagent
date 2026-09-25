package com.agentflow.web.run;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.TerminationReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunSnapshotTest {

    private static final Instant CREATED = Instant.parse("2026-09-15T08:00:00Z");
    private static final Instant STARTED = CREATED.plusSeconds(1);
    private static final Instant FINISHED = CREATED.plusSeconds(2);

    @Test
    void representsQueuedRunWithoutTerminalOrRuntimeFields() {
        RunSnapshot snapshot = queued();

        assertThat(snapshot.taskId()).isEqualTo("run-1");
        assertThat(snapshot.runId()).isEqualTo(snapshot.taskId());
        assertThat(snapshot.status()).isEqualTo(RunLifecycleStatus.QUEUED);
        assertThat(snapshot.startedAt()).isNull();
        assertThat(snapshot.finishedAt()).isNull();
        assertThat(snapshot.terminationReason()).isNull();
        assertThat(snapshot.runtimeReason()).isNull();
        assertThat(snapshot.finalAnswer()).isNull();
        assertThat(snapshot.usage()).isNull();
        assertThat(snapshot.recordingComplete()).isFalse();
    }

    @Test
    void successfulTerminalSnapshotPreservesRuntimeUsage() {
        TokenUsage usage = new TokenUsage(7, 3, 10);
        RunSnapshot snapshot = new RunSnapshot("run-1", "run-1", "session-1",
                RunLifecycleStatus.SUCCEEDED, "hello", "answer", CREATED, FINISHED,
                STARTED, FINISHED, false, RunTerminationReason.COMPLETED,
                TerminationReason.COMPLETED, null, true, usage);

        assertThat(snapshot.usage()).isSameAs(usage);
        assertThat(snapshot.finalAnswer()).isEqualTo("answer");
        assertThat(snapshot.terminationReason()).isEqualTo(RunTerminationReason.COMPLETED);
        assertThat(snapshot.runtimeReason()).isEqualTo(TerminationReason.COMPLETED);
    }

    @Test
    void rejectsDifferentTaskAndRunIdentifiers() {
        assertThatThrownBy(() -> new RunSnapshot("task-1", "run-2", "session-1",
                RunLifecycleStatus.QUEUED, "hello", null, CREATED, CREATED,
                null, null, false, null, null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void rejectsTerminalDataOnNonTerminalSnapshot() {
        assertThatThrownBy(() -> new RunSnapshot("run-1", "run-1", "session-1",
                RunLifecycleStatus.RUNNING, "hello", null, CREATED, FINISHED,
                STARTED, FINISHED, false, RunTerminationReason.INTERNAL_ERROR,
                null, "INTERNAL_ERROR", false, TokenUsage.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-terminal");
    }

    @Test
    void rejectsIllegalTerminalStatusReasonAndAnswerCombinations() {
        assertThatThrownBy(() -> new RunSnapshot("run-1", "run-1", "session-1",
                RunLifecycleStatus.SUCCEEDED, "hello", null, CREATED, FINISHED,
                STARTED, FINISHED, false, RunTerminationReason.COMPLETED,
                TerminationReason.COMPLETED, null, true, TokenUsage.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answer");

        assertThatThrownBy(() -> new RunSnapshot("run-1", "run-1", "session-1",
                RunLifecycleStatus.FAILED, "hello", "must not escape", CREATED, FINISHED,
                STARTED, FINISHED, false, RunTerminationReason.INTERNAL_ERROR,
                null, "INTERNAL_ERROR", false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answer");

        assertThatThrownBy(() -> new RunSnapshot("run-1", "run-1", "session-1",
                RunLifecycleStatus.CANCELLED, "hello", null, CREATED, FINISHED,
                null, FINISHED, true, RunTerminationReason.INTERNAL_ERROR,
                null, "INTERNAL_ERROR", false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    @Test
    void rejectsMissingOrMismatchedFailureCode() {
        assertThatThrownBy(() -> new RunSnapshot("run-1", "run-1", "session-1",
                RunLifecycleStatus.FAILED, "hello", null, CREATED, FINISHED,
                STARTED, FINISHED, false, RunTerminationReason.MODEL_ERROR,
                TerminationReason.MODEL_ERROR, "INTERNAL_ERROR", false, TokenUsage.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorCode");
    }

    @Test
    void waitingApprovalIsNonTerminalAndRequiresOriginalStart() {
        RunLifecycleStatus waiting = RunLifecycleStatus.valueOf("WAITING_APPROVAL");
        assertThat(waiting.isTerminal()).isFalse();
        RunSnapshot snapshot = new RunSnapshot("run-1", "run-1", "session-1", waiting,
                "hello", null, CREATED, STARTED, STARTED, null,
                false, null, null, null, false, null);
        assertThat(snapshot.startedAt()).isEqualTo(STARTED);
        assertThatThrownBy(() -> new RunSnapshot("run-1", "run-1", "session-1", waiting,
                "hello", null, CREATED, STARTED, null, null,
                false, null, null, null, false, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private RunSnapshot queued() {
        return new RunSnapshot("run-1", "run-1", "session-1", RunLifecycleStatus.QUEUED,
                "hello", null, CREATED, CREATED, null, null, false,
                null, null, null, false, null);
    }
}
