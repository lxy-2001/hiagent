package com.agentflow.rag.retrieval;

import com.agentflow.rag.corpus.CorpusManifest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvaluationTest {
    @Test void writesMeasuredKeywordAndFixedVectorRecallForEveryGoldQuery() throws Exception {
        Path root = Path.of(getClass().getResource("/corpus/feature005/").toURI());
        var corpus = CorpusManifest.read(root.resolve("documents"), new CorpusManifest.IndexProfile("evaluation", "lexical-fixture", 3, 800, 100));
        var texts = new HashMap<String,String>();
        var paths = new HashMap<String,String>();
        corpus.documents().forEach(doc -> doc.chunks().forEach(chunk -> { texts.put(chunk.chunkId(),chunk.text()); paths.put(chunk.chunkId(),doc.relativePath()); }));
        var index = new KeywordIndex(texts);
        var mapper = new JsonMapper();
        var questions = mapper.readTree(Files.readString(root.resolve("questions.json"))).path("questions");
        var results = new ArrayList<Map<String,Object>>();
        double keywordTotal=0, fusedTotal=0;
        for (var question : questions) {
            String query = question.path("query").asText();
            var gold = new HashSet<String>();
            for (var span : question.path("gold")) {
                corpus.documents().stream().filter(doc -> doc.relativePath().equals(span.path("sourcePath").asText()))
                        .flatMap(doc -> doc.chunks().stream())
                        .filter(chunk -> chunk.start() <= span.path("start").asInt() && chunk.end() >= span.path("end").asInt())
                        .forEach(chunk -> gold.add(chunk.chunkId()));
            }
            assertThat(gold).isNotEmpty();
            var keyword = index.query(query);
            var queryTerms = KeywordIndex.terms(query);
            var vectors = texts.entrySet().stream().map(entry -> {
                var terms=KeywordIndex.terms(entry.getValue());
                long overlap=terms.stream().filter(queryTerms::contains).count();
                double cosine=terms.isEmpty() || queryTerms.isEmpty()?0:overlap/Math.sqrt((double)terms.size()*queryTerms.size());
                return new ReciprocalRankFusion.Candidate(entry.getKey(),cosine);
            }).filter(candidate -> candidate.score()>=.35).toList();
            var keywordIds=keyword.stream().limit(5).map(ReciprocalRankFusion.Candidate::chunkId).toList();
            var fusedIds=new ReciprocalRankFusion().fuse(vectors,keyword,5).stream().map(ReciprocalRankFusion.Candidate::chunkId).toList();
            double keywordRecall=recall(keywordIds,gold), fusedRecall=recall(fusedIds,gold);
            keywordTotal+=keywordRecall; fusedTotal+=fusedRecall;
            results.add(Map.of("id",question.path("id").asText(),"query",query,"goldChunkIds",gold,
                    "keywordChunkIds",keywordIds,"fusedChunkIds",fusedIds,"keywordRecallAt5",keywordRecall,"fixedVectorRecallAt5",fusedRecall));
        }
        double keywordRecall=keywordTotal/questions.size(), fusedRecall=fusedTotal/questions.size();
        var report=Map.of("schemaVersion",1,"snapshotId",corpus.snapshotId(),"mode","OFFLINE_LEXICAL_FEATURE_VECTORS",
                "realEmbedding",false,"questionCount",questions.size(),"keywordRecallAt5",keywordRecall,"fixedVectorRecallAt5",fusedRecall,"questions",results);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/feature005-evaluation.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(questions.size()).isGreaterThanOrEqualTo(20);
        assertThat(keywordRecall).isGreaterThanOrEqualTo(.8);
        assertThat(fusedRecall).isGreaterThanOrEqualTo(.8);
    }
    private static double recall(List<String> ids,Set<String> gold) {
        return (double)gold.stream().filter(ids::contains).count()/gold.size();
    }
}
