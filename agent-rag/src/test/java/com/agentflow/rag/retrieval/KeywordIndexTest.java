package com.agentflow.rag.retrieval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.*;

class KeywordIndexTest {
    @Test
    void ranksIntersectionsAndLimitsToFirst64UniqueQueryTerms() {
        var index = new KeywordIndex(Map.of("b", "java", "a", "java spring", "c", "term64"));
        assertThat(index.query("java spring")).extracting(ReciprocalRankFusion.Candidate::chunkId).containsExactly("a", "b");
        assertThat(index.query("java")).extracting(ReciprocalRankFusion.Candidate::chunkId).containsExactly("a", "b");
        assertThat(index.query("absent")).isEmpty();
        String query = java.util.stream.IntStream.rangeClosed(0, 64).mapToObj(i -> "term" + i)
                .collect(java.util.stream.Collectors.joining(" "));
        assertThat(index.query(query)).isEmpty();
    }

    @Test
    void actualKeywordRecallAtFiveMeetsGoldThreshold() throws Exception {
        Path root = Path.of(getClass().getResource("/corpus/feature005/").toURI());
        var texts = new HashMap<String, String>();
        try (var paths = Files.list(root.resolve("documents"))) {
            for (Path path : paths.toList()) { texts.put(path.getFileName().toString(), Files.readString(path)); }
        }
        var index = new KeywordIndex(texts);
        var questions = new JsonMapper().readTree(Files.readString(root.resolve("questions.json"))).get("questions");
        int matched = 0;
        int fusedMatched = 0;
        var fusion = new ReciprocalRankFusion();
        for (var question : questions) {
            var actual = index.query(question.get("query").asString()).stream().limit(5)
                    .map(ReciprocalRankFusion.Candidate::chunkId).toList();
            boolean found = false;
            for (var gold : question.get("gold")) { found |= actual.contains(gold.get("sourcePath").asString()); }
            if (found) { matched++; }
            // Fixed lexical feature vectors computed from all source texts, never from gold labels.
            var queryTerms = KeywordIndex.terms(question.get("query").asString());
            var semantic = texts.entrySet().stream().map(entry -> {
                var terms = KeywordIndex.terms(entry.getValue());
                long intersection = terms.stream().filter(queryTerms::contains).count();
                double cosine = queryTerms.isEmpty() || terms.isEmpty() ? 0 : intersection / Math.sqrt((double) terms.size() * queryTerms.size());
                return new ReciprocalRankFusion.Candidate(entry.getKey(), cosine);
            }).filter(candidate -> candidate.score() >= .35).toList();
            var fused = fusion.fuse(semantic, index.query(question.get("query").asString()), 5).stream()
                    .map(ReciprocalRankFusion.Candidate::chunkId).toList();
            boolean fusedFound = false;
            for (var gold : question.get("gold")) { fusedFound |= fused.contains(gold.get("sourcePath").asString()); }
            if (fusedFound) { fusedMatched++; }
        }
        assertThat(questions.size()).isGreaterThanOrEqualTo(20);
        assertThat((double) matched / questions.size()).isGreaterThanOrEqualTo(.8);
        assertThat((double) fusedMatched / questions.size()).isGreaterThanOrEqualTo(.8);
        System.out.println("Feature005 keyword Recall@5=" + matched + "/" + questions.size());
        System.out.println("Feature005 fixed-vector fusion Recall@5=" + fusedMatched + "/" + questions.size());
    }
}
