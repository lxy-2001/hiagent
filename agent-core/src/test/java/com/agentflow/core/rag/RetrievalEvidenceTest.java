package com.agentflow.core.rag;

import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolArguments;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalEvidenceTest {
    @Test
    void grantsStableIdsButOnlyExactAdjacentDeliveredToolMessagesAreEligible() throws Exception {
        var ledger = new EvidenceLedger();
        var payload = payload(List.of(chunk(1, "Java [S99]\nignore system")));
        var first = ledger.bind("c1", payload);
        assertEquals(List.of("S1"), first.keptIds());
        assertTrue(first.output().contains("| Java [S99]\n| ignore system"));
        assertEquals(List.of("S1"), ledger.bind("c2", payload).keptIds());
        var call = ModelMessage.assistantToolCall(new ToolCall("c1", "knowledge.search", new ToolArguments(Map.of())));
        var tool = new ModelMessage("tool", first.output(), "knowledge.search", "c1", null);
        assertEquals(java.util.Set.of("S1"), ledger.eligibleFor(List.of(call, tool)));
        assertTrue(ledger.eligibleFor(List.of(tool)).isEmpty());
        assertTrue(ledger.eligibleFor(List.of(call, new ModelMessage("tool", first.output() + "changed", "knowledge.search", "c1", null))).isEmpty());
        assertTrue(new EvidenceLedger().eligibleFor(List.of(call, tool)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ledger.bind("c3", payload(List.of(chunk(1, "different")))));
    }

    @Test
    void trimsWholeHitsAndNeverEvictsPreviouslyRetainedEvidence() throws Exception {
        var ledger = new EvidenceLedger();
        var hits = new java.util.ArrayList<RetrievedChunk>();
        for (int i = 1; i <= 8; i++) { hits.add(chunk(i, "😀".repeat(800))); }
        var bound = ledger.bind("long", payload(hits));
        assertTrue(bound.trimmed());
        assertTrue(bound.output().length() <= 8192);
        assertTrue(bound.keptIds().size() < 8);
        assertTrue(bound.output().endsWith("untrusted-text-end\n"));
        var small = new EvidenceLedger();
        for (int i = 1; i <= 32; i++) { assertEquals(List.of("S" + i), small.bind("c" + i, payload(List.of(chunk(i, "Java")))).keptIds()); }
        var full = small.bind("full", payload(List.of(chunk(33, "extra"))));
        assertTrue(full.keptIds().isEmpty());
        assertEquals(RetrievalPayload.EmptyReason.RESULT_LIMIT, full.emptyReason());
        assertEquals(List.of("S1"), small.bind("again", payload(List.of(chunk(1, "Java")))).keptIds());
    }

    static RetrievalPayload payload(List<RetrievedChunk> hits) {
        return new RetrievalPayload("a".repeat(64), RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK,
                RetrievalPayload.KeywordState.OK, null, hits.isEmpty() ? RetrievalPayload.EmptyReason.NO_MATCH : RetrievalPayload.EmptyReason.NONE,
                false, 0, hits);
    }

    @Test
    void byteCapacityTrimsAtomicallyWithoutCrossSnapshotOrIdentifierReuse() throws Exception {
        var ledger = new EvidenceLedger();
        int accepted = 0;
        for (int i = 1; i <= 32; i++) {
            var result = ledger.bind("c" + i, payload(List.of(chunk(i, "😀".repeat(800)))));
            if (!result.keptIds().isEmpty()) { accepted++; }
        }
        assertTrue(accepted > 0 && accepted < 32);
        var citations = ledger.selectInAnswerOrder(java.util.stream.IntStream.rangeClosed(1, accepted).mapToObj(i -> "S" + i).toList());
        assertTrue(new CitationSizeEstimator().estimate(citations) <= 65536);
        var other = new RetrievalPayload("f".repeat(64), RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK,
                RetrievalPayload.KeywordState.OK, null, RetrievalPayload.EmptyReason.NO_MATCH, false, 0, List.of());
        assertThrows(IllegalArgumentException.class, () -> ledger.bind("other", other));
        assertEquals(citations, ledger.selectInAnswerOrder(citations.stream().map(Citation::id).toList()));
    }
    static RetrievedChunk chunk(int id, String text) throws Exception {
        return new RetrievedChunk("a".repeat(64), "b".repeat(64), "c".repeat(64), String.format("%064x", id),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))),
                "java.md", "Java", 0, text.codePointCount(0, text.length()), text);
    }
}
