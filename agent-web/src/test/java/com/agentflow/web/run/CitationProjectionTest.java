package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.rag.Citation;
import com.agentflow.core.rag.CitationSizeEstimator;
import com.agentflow.core.runtime.RunStatus;
import com.agentflow.core.runtime.TerminationReason;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CitationProjectionTest {
    @Test void oversizedActualJsonBecomesFailedProjectionWithoutCitations() throws Exception {
        var base = citation("😀".repeat(800));
        var citations = java.util.stream.IntStream.rangeClosed(1, 32).mapToObj(i -> new Citation(
                "S" + i, base.snapshotId(), base.docId(), base.documentVersion(), String.format("%064x", i),
                base.contentHash(), base.sourcePath(), base.title(), base.start(), base.end(), base.excerpt())).toList();
        var result = new AgentResult("r", "answer [S1]", List.of(), RunStatus.SUCCEEDED,
                TerminationReason.COMPLETED, TokenUsage.empty(), "", citations);
        var projection = new RunResultProjector().project("r", result, Instant.now(), false, true);
        assertThat(projection.terminationReason()).isEqualTo(RunTerminationReason.OUTPUT_TOO_LARGE);
        assertThat(projection.citations()).isEmpty();
        assertThat(projection.finalAnswer()).isNull();
    }
    @Test void preservesEvidenceEvenWhenStepRecordingIsIncomplete() throws Exception {
        var citation = citation("Java 中文 😀 \" \\ \n");
        var result = new AgentResult("r", "Answer [S1]", List.of(), RunStatus.SUCCEEDED,
                TerminationReason.COMPLETED, TokenUsage.empty(), "", List.of(citation));
        var projection = new RunResultProjector().project("r", result, Instant.now(), false, false);
        var json = new ObjectMapper().valueToTree(projection);
        assertThat(json.path("citations").isArray()).isTrue();
        assertThat(json.path("citations").get(0).path("excerpt").asText()).isEqualTo(citation.excerpt());
        assertThat(projection.recordingComplete()).isFalse();
    }

    @Test void projectsBothCitationFailureReasonsWithoutSuccessfulAnswer() {
        for (var reason : List.of(TerminationReason.CITATION_INVALID, TerminationReason.INSUFFICIENT_EVIDENCE)) {
            var result = AgentResult.failure("r", RunStatus.FAILED, reason, "rejected", List.of(), TokenUsage.empty());
            var projection = new RunResultProjector().project("r", result, Instant.now(), false, false);
            assertThat(projection.terminationReason().name()).isEqualTo(reason.name());
            assertThat(projection.finalAnswer()).isNull();
        }
    }

    @Test void coreEstimateBoundsRealJacksonEnvelopeBytes() throws Exception {
        var citations = List.of(citation("Java 中文 😀 \" \\ \n\t".repeat(30)));
        byte[] bytes = new ObjectMapper().writeValueAsBytes(java.util.Map.of("schemaVersion", 1, "citations", citations));
        assertThat(new CitationSizeEstimator().estimate(citations)).isGreaterThanOrEqualTo(bytes.length);
    }

    static Citation citation(String text) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        return new Citation("S1", "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), hash,
                "java.md", "Java", 0, text.codePointCount(0, text.length()), text);
    }
}
