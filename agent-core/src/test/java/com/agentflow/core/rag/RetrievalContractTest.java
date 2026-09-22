package com.agentflow.core.rag;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalContractTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void validatesRequestBounds() {
        assertEquals(5, new RetrievalRequest("Java").topK());
        assertThrows(IllegalArgumentException.class, () -> new RetrievalRequest(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalRequest("a".repeat(513), 1));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalRequest("query", 0));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalRequest("query", 9));
    }

    @Test
    void validatesSourceHashRangePathAndUnicode() throws Exception {
        assertEquals("Java😀", chunk("Java😀", "java.md", 0, 5).text());
        assertThrows(IllegalArgumentException.class, () -> chunk("Java😀", "java.md", 0, 6));
        for (String path : List.of("../secret.md", "/java.md", "x\\java.md", "a//b.md", "a/./b.md", "C:java.md")) {
            assertThrows(IllegalArgumentException.class, () -> chunk("Java", path, 0, 4));
        }
        assertThrows(IllegalArgumentException.class, () -> chunk("\uD800", "java.md", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> chunk("a".repeat(801), "java.md", 0, 801));
        assertThrows(IllegalArgumentException.class, () -> new RetrievedChunk(HASH, HASH, HASH, HASH, HASH, "java.md", "Java", 0, 4, "Java"));
    }

    @Test
    void copiesHitsAndEnforcesModeAndEmptyInvariants() throws Exception {
        var hits = new ArrayList<>(List.of(chunk("Java", "java.md", 0, 4)));
        var payload = payload(hits, RetrievalPayload.EmptyReason.NONE);
        hits.clear();
        assertEquals(1, payload.hits().size());
        assertThrows(UnsupportedOperationException.class, () -> payload.hits().clear());
        assertThrows(IllegalArgumentException.class, () -> payload(List.of(), RetrievalPayload.EmptyReason.NONE));
        assertThrows(IllegalArgumentException.class, () -> payload(payload.hits(), RetrievalPayload.EmptyReason.NO_MATCH));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalPayload(HASH, RetrievalPayload.Mode.DEGRADED_KEYWORD,
                RetrievalPayload.SemanticState.OK, RetrievalPayload.KeywordState.OK, null,
                RetrievalPayload.EmptyReason.NO_MATCH, false, 0, List.of()));
    }

    @Test
    void validatesCitationIdentifiersAndSources() throws Exception {
        var c = chunk("Java", "java.md", 0, 4);
        assertEquals("Java", citation("S32", c).excerpt());
        for (String id : List.of("S0", "S33", "S01", "S-1", "s1")) {
            assertThrows(IllegalArgumentException.class, () -> citation(id, c));
        }
    }

    private Citation citation(String id, RetrievedChunk c) {
        return new Citation(id, c.snapshotId(), c.docId(), c.documentVersion(), c.chunkId(), c.contentHash(),
                c.relativePath(), c.title(), c.start(), c.end(), c.text());
    }

    @Test
    void retainsLegacyDefaultsAndRejectsInvalidAttachments() throws Exception {
        assertFalse(new com.agentflow.core.AgentRequest("t", "s", "u", "question").requireEvidence());
        assertNull(com.agentflow.core.tool.ToolResult.success("echo", "ok").retrievalPayload());
        assertTrue(com.agentflow.core.AgentResult.success("t", "answer", List.of(),
                com.agentflow.core.chat.TokenUsage.empty()).citations().isEmpty());
        var payload = payload(List.of(chunk("Java", "java.md", 0, 4)), RetrievalPayload.EmptyReason.NONE);
        assertEquals(payload, com.agentflow.core.tool.ToolResult.retrieval("knowledge.search", payload).retrievalPayload());
        assertThrows(IllegalArgumentException.class, () -> com.agentflow.core.tool.ToolResult.retrieval("echo", payload));
        assertThrows(IllegalArgumentException.class, () -> new com.agentflow.core.tool.ToolResult("knowledge.search", null,
                com.agentflow.core.tool.ToolResultStatus.FAILED, "ERROR", null, false, "c", payload));
        var citation = citation("S1", payload.hits().get(0));
        assertThrows(IllegalArgumentException.class, () -> new com.agentflow.core.AgentResult("t", null, List.of(),
                com.agentflow.core.runtime.RunStatus.FAILED, com.agentflow.core.runtime.TerminationReason.MODEL_ERROR,
                com.agentflow.core.chat.TokenUsage.empty(), "failed", List.of(citation)));
        assertThrows(IllegalArgumentException.class, () -> new com.agentflow.core.AgentResult("t", "answer", List.of(),
                com.agentflow.core.runtime.RunStatus.SUCCEEDED, com.agentflow.core.runtime.TerminationReason.COMPLETED,
                com.agentflow.core.chat.TokenUsage.empty(), "", List.of(citation, citation)));
    }

    @Test
    void legacyRetrieverCannotInventEvidence() {
        RagRetriever retriever = (query, limit) -> List.of(new RagDocument("legacy", "title", "body", 1));
        assertFalse(retriever.supportsEvidence());
        assertThrows(UnsupportedOperationException.class, () -> retriever.retrieve(new RetrievalRequest("Java"),
                new com.agentflow.core.tool.ToolContext("t", "s", "u").control()));
    }

    @Test
    void rejectsMixedSnapshotsDuplicateHitsAndInvalidMetadata() throws Exception {
        var c = chunk("Java", "java.md", 0, 4);
        assertThrows(IllegalArgumentException.class, () -> payload(List.of(c, c), RetrievalPayload.EmptyReason.NONE));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalPayload("b".repeat(64),
                RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK, RetrievalPayload.KeywordState.OK,
                null, RetrievalPayload.EmptyReason.NONE, false, 0, List.of(c)));
        assertThrows(IllegalArgumentException.class, () -> new RetrievedChunk(HASH, HASH, HASH, HASH,
                c.contentHash(), "a".repeat(257), "title", 0, 4, "Java"));
        assertThrows(IllegalArgumentException.class, () -> new RetrievedChunk(HASH, HASH, HASH, HASH,
                c.contentHash(), "java.md", "a".repeat(121), 0, 4, "Java"));
        assertThrows(IllegalArgumentException.class, () -> new RetrievedChunk("bad", HASH, HASH, HASH,
                c.contentHash(), "java.md", "title", 0, 4, "Java"));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalPayload(HASH, RetrievalPayload.Mode.HYBRID,
                RetrievalPayload.SemanticState.OK, RetrievalPayload.KeywordState.OK, null,
                RetrievalPayload.EmptyReason.RESULT_LIMIT, false, 0, List.of()));
    }

    private RetrievalPayload payload(List<RetrievedChunk> hits, RetrievalPayload.EmptyReason empty) {
        return new RetrievalPayload(HASH, RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK,
                RetrievalPayload.KeywordState.OK, null, empty, false, 0, hits);
    }

    private RetrievedChunk chunk(String text, String path, int start, int end) throws Exception {
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        return new RetrievedChunk(HASH, HASH, HASH, HASH, hash, path, "Java", start, end, text);
    }
}
