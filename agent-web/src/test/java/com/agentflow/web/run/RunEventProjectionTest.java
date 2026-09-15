package com.agentflow.web.run;

import com.agentflow.core.AgentEvent;
import com.agentflow.core.AgentStepType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RunEventProjectionTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-15T10:00:00Z");
    private final RunEventProjector projector = new RunEventProjector();

    @Test
    void projectsOnlyTheAgentStepWhitelistAndKeepsCoreSequenceDistinctFromCorrelation() throws Exception {
        AgentEvent core = new AgentEvent("run-1", AgentStepType.TOOL_RESULT, "weather",
                "sunny", OCCURRED_AT, Long.MAX_VALUE, "call-77", false);

        RunEvent.Draft event = projector.project("run-1", core, OCCURRED_AT).event().orElseThrow();
        RunEvent.AgentStepPayload payload = (RunEvent.AgentStepPayload) event.payload();

        assertThat(event.taskId()).isEqualTo("run-1");
        assertThat(event.runId()).isEqualTo("run-1");
        assertThat(event.type()).isEqualTo(RunEvent.Type.AGENT_STEP);
        assertThat(payload.coreSequence()).isEqualTo("9223372036854775807");
        assertThat(payload.correlationId()).isEqualTo("call-77");
        assertThat(payload).extracting("coreSequence", "stepType", "name", "summary",
                "correlationId", "runtimeTerminal", "summaryTruncated")
                .containsExactly("9223372036854775807", "TOOL_RESULT", "weather", "sunny",
                        "call-77", false, false);
        String json = new ObjectMapper().writeValueAsString(event);
        assertThat(json).doesNotContain("stepNo", "finalAnswer", "diagnostic");
        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThan(16_384);
    }

    @Test
    void acceptsZeroSequenceAndRedactsBeforeUnicodeSafeLimits() {
        AgentEvent core = new AgentEvent("run-1", AgentStepType.MODEL_DECISION,
                "n".repeat(101), "token=x;" + "中".repeat(1_020) + "😀tail",
                OCCURRED_AT, 0, "c".repeat(129), true);

        RunEventProjector.Projection projection = projector.project("run-1", core, OCCURRED_AT);
        RunEvent.AgentStepPayload payload =
                (RunEvent.AgentStepPayload) projection.event().orElseThrow().payload();

        assertThat(projection.observationComplete()).isTrue();
        assertThat(payload.coreSequence()).isEqualTo("0");
        assertThat(payload.name()).hasSize(100);
        assertThat(payload.correlationId()).hasSize(128);
        assertThat(payload.summary()).doesNotContain("token=x").hasSizeLessThanOrEqualTo(1_024);
        assertThat(Character.isHighSurrogate(payload.summary().charAt(payload.summary().length() - 1)))
                .isFalse();
        assertThat(payload.summaryTruncated()).isTrue();
        assertThat(payload.runtimeTerminal()).isTrue();
    }

    @Test
    void dropsWrongOwnershipWithoutReroutingAndMarksObservationIncomplete() {
        AgentEvent core = new AgentEvent("other-run", AgentStepType.LLM, "model", "answer",
                OCCURRED_AT, 4, "decision-4", false);

        RunEventProjector.Projection projection = projector.project("run-1", core, OCCURRED_AT);

        assertThat(projection.event()).isEmpty();
        assertThat(projection.observationComplete()).isFalse();
    }

    @Test
    void replacesProjectionFailureWithFixedSafeSummary() {
        RunEventProjector.Projection projection = projector.project("run-1", null, OCCURRED_AT);
        RunEvent.AgentStepPayload payload =
                (RunEvent.AgentStepPayload) projection.event().orElseThrow().payload();

        assertThat(projection.observationComplete()).isFalse();
        assertThat(payload.summary()).isEqualTo("EVENT_CONTENT_UNAVAILABLE");
        assertThat(payload.summaryTruncated()).isTrue();
        assertThat(payload.coreSequence()).isEqualTo("0");
    }
}
