package com.agentflow.core.rag;

import com.agentflow.core.model.ModelMessage;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;

public final class EvidenceLedger {
    public record BoundRetrieval(String output, List<String> keptIds, boolean trimmed,
                                 RetrievalPayload.EmptyReason emptyReason, String summary) {
        public BoundRetrieval { keptIds = List.copyOf(keptIds); }
    }
    private final Map<String, Citation> chunks = new LinkedHashMap<>();
    private final Map<String, BoundRetrieval> calls = new LinkedHashMap<>();
    private final RetrievalTextRenderer renderer = new RetrievalTextRenderer();
    private final CitationSizeEstimator estimator = new CitationSizeEstimator();
    private String snapshotId;

    public BoundRetrieval bind(String callId, RetrievalPayload payload) {
        if (callId == null || callId.isBlank() || calls.containsKey(callId)
                || snapshotId != null && !snapshotId.equals(payload.snapshotId())) {
            throw new IllegalArgumentException("RAG_SOURCE_INVALID");
        }
        for (var hit : payload.hits()) {
            var existing = chunks.get(hit.chunkId());
            if (existing != null && !existing.equals(citation(existing.id(), hit))) {
                throw new IllegalArgumentException("RAG_SOURCE_INVALID");
            }
        }
        var staged = new LinkedHashMap<>(chunks);
        var kept = new ArrayList<Citation>();
        boolean trimmed = payload.trimmed();
        for (var hit : payload.hits()) {
            Citation source = staged.get(hit.chunkId());
            boolean added = source == null;
            if (added && staged.size() >= 32) { trimmed = true; break; }
            if (added) { source = citation("S" + (staged.size() + 1), hit); }
            var candidate = new LinkedHashMap<>(staged);
            candidate.put(hit.chunkId(), source);
            var candidateHits = new ArrayList<>(kept);
            candidateHits.add(source);
            if (estimator.estimate(candidate.values()) > 65536
                    || renderer.render(payload, candidateHits, false, RetrievalPayload.EmptyReason.NONE).length() > 8192) {
                trimmed = true; break;
            }
            staged = candidate;
            kept = candidateHits;
        }
        var empty = kept.isEmpty() ? payload.hits().isEmpty() ? payload.emptyReason() : RetrievalPayload.EmptyReason.RESULT_LIMIT
                : RetrievalPayload.EmptyReason.NONE;
        String output = renderer.render(payload, kept, trimmed, empty);
        String summary = "mode=" + payload.mode() + " semantic=" + payload.semanticState() + " keyword=" + payload.keywordState()
                + " hits=" + payload.hits().size() + " kept=" + kept.size() + " trimmed=" + trimmed + " empty=" + empty
                + " reason=" + (payload.degradationCode() == null ? "NONE" : payload.degradationCode()) + " elapsedMs=" + payload.elapsedMillis();
        var bound = new BoundRetrieval(output, kept.stream().map(Citation::id).toList(), trimmed, empty, summary);
        chunks.clear(); chunks.putAll(staged);
        snapshotId = payload.snapshotId();
        calls.put(callId, bound);
        return bound;
    }

    public Set<String> eligibleFor(List<ModelMessage> messages) {
        var eligible = new HashSet<String>();
        for (int i = 1; i < messages.size(); i++) {
            var message = messages.get(i);
            var previous = messages.get(i - 1);
            var bound = calls.get(message.toolCallId());
            if ("tool".equals(message.role()) && "knowledge.search".equals(message.name()) && bound != null
                    && bound.output().equals(message.content()) && "assistant".equals(previous.role()) && previous.toolCall() != null
                    && "knowledge.search".equals(previous.toolCall().name()) && message.toolCallId().equals(previous.toolCall().callId())) {
                eligible.addAll(bound.keptIds());
            }
        }
        return Set.copyOf(eligible);
    }

    public List<Citation> selectInAnswerOrder(List<String> ids) {
        var result = new ArrayList<Citation>();
        for (String id : ids) {
            result.add(chunks.values().stream().filter(c -> c.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("CITATION_INVALID")));
        }
        return List.copyOf(result);
    }

    private Citation citation(String id, RetrievedChunk hit) {
        return new Citation(id, hit.snapshotId(), hit.docId(), hit.documentVersion(), hit.chunkId(), hit.contentHash(),
                hit.relativePath(), hit.title(), hit.start(), hit.end(), hit.text());
    }
}
