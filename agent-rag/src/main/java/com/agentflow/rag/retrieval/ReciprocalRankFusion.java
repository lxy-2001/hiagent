package com.agentflow.rag.retrieval;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;

public final class ReciprocalRankFusion {
    public record Candidate(String chunkId, double score) { }
    static final Comparator<Candidate> ORDER = Comparator.comparingDouble(Candidate::score).reversed()
            .thenComparing(Candidate::chunkId);

    public List<Candidate> fuse(List<Candidate> semantic, List<Candidate> keyword, int topK) {
        if (topK < 1 || topK > 8) { throw new IllegalArgumentException("topK must be 1..8"); }
        var scores = new HashMap<String, Double>();
        accumulate(semantic, scores);
        accumulate(keyword, scores);
        return scores.entrySet().stream().map(entry -> new Candidate(entry.getKey(), entry.getValue()))
                .sorted(ORDER).limit(topK).toList();
    }

    private void accumulate(List<Candidate> channel, Map<String, Double> scores) {
        var unique = new HashMap<String, Double>();
        for (var candidate : channel) {
            if (candidate.chunkId() == null || candidate.chunkId().isBlank() || !Double.isFinite(candidate.score())) {
                throw new IllegalArgumentException("invalid ranked candidate");
            }
            unique.merge(candidate.chunkId(), candidate.score(), Math::max);
        }
        var ranked = unique.entrySet().stream().map(entry -> new Candidate(entry.getKey(), entry.getValue()))
                .sorted(ORDER).limit(40).toList();
        for (int i = 0; i < ranked.size(); i++) { scores.merge(ranked.get(i).chunkId(), 1d / (61 + i), Double::sum); }
    }
}
