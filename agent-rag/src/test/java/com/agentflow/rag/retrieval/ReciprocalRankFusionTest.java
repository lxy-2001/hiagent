package com.agentflow.rag.retrieval;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ReciprocalRankFusionTest {
    @Test
    void sumsActualRanksDeduplicatesAndBreaksTiesByIdentity() {
        var fusion = new ReciprocalRankFusion();
        var result = fusion.fuse(List.of(c("a", .9), c("b", .8), c("a", .7)), List.of(c("b", 1), c("a", .5)), 8);
        assertThat(result).extracting(ReciprocalRankFusion.Candidate::chunkId).containsExactly("a", "b");
        assertThat(result.get(0).score()).isCloseTo(1d / 61 + 1d / 62, within(1e-12));
        assertThat(fusion.fuse(List.of(), List.of(c("z", 1)), 1).get(0).score()).isEqualTo(1d / 61);
        assertThat(fusion.fuse(List.of(), List.of(), 5)).isEmpty();
    }
    private ReciprocalRankFusion.Candidate c(String id, double score) { return new ReciprocalRankFusion.Candidate(id, score); }
}
