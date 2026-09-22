package com.agentflow.web.run;

import com.agentflow.core.rag.Citation;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Versioned, bounded terminal evidence; never reconstructs evidence from an index. */
public final class CitationSnapshotCodec {
    public static final int MAX_BYTES = 65_536;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private record Envelope(int schemaVersion, List<Citation> citations) { }

    public String encode(List<Citation> citations) {
        validate(citations);
        String json = MAPPER.writeValueAsString(new Envelope(1, citations));
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("citation snapshot exceeds byte limit");
        }
        return json;
    }

    public List<Citation> decode(String json) {
        if (json == null) { return List.of(); }
        try {
            if (json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new UnavailableException();
            }
            var root = MAPPER.readTree(json);
            if (!root.isObject() || root.size() != 2 || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").asInt() != 1 || !root.path("citations").isArray()
                    || root.path("citations").size() > 32) { throw new UnavailableException(); }
            var fields = java.util.Set.of("id", "snapshotId", "docId", "documentVersion", "chunkId",
                    "contentHash", "sourcePath", "title", "start", "end", "excerpt");
            for (var node : root.path("citations")) {
                if (!node.isObject() || node.size() != fields.size()) { throw new UnavailableException(); }
                for (String field : fields) {
                    boolean numeric = field.equals("start") || field.equals("end");
                    if (numeric ? !node.path(field).isIntegralNumber() || !node.path(field).canConvertToInt()
                            : !node.path(field).isString()) { throw new UnavailableException(); }
                }
            }
            var envelope = MAPPER.treeToValue(root, Envelope.class);
            validate(envelope.citations());
            return List.copyOf(envelope.citations());
        } catch (RuntimeException invalid) {
            throw new UnavailableException();
        }
    }

    private static void validate(List<Citation> citations) {
        if (citations == null || citations.size() > 32) { throw new IllegalArgumentException("invalid citations"); }
        var ids = new java.util.HashSet<String>();
        var chunks = new java.util.HashSet<String>();
        for (Citation citation : citations) {
            if (citation == null || !ids.add(citation.id()) || !chunks.add(citation.chunkId())
                    || !citations.get(0).snapshotId().equals(citation.snapshotId())) {
                throw new IllegalArgumentException("invalid citations");
            }
        }
    }

    public static final class UnavailableException extends RuntimeException {
        public UnavailableException() { super("CITATION_DATA_UNAVAILABLE"); }
    }
}
