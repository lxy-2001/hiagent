package com.agentflow.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class KeywordCorpusIndexTest {
    @Test
    void buildsDistinctAsciiAndOverlappingHanTerms() {
        assertThat(KeywordIndex.terms("Java JAVA a_b 123 中文检索 人 ab"))
                .containsExactly("java", "a_b", "123", "中文", "文检", "检索", "人", "ab");
        var index = new KeywordIndex(Map.of("c1", "Java Java 中文", "c2", "Java"));
        assertThat(index.termCount()).isEqualTo(2);
        assertThat(index.postingCount()).isEqualTo(3);
        assertThat(KeywordIndex.terms("x".repeat(65))).isEmpty();
    }

    @Test
    void rejectsTooManyDistinctTermsWithoutSilentlyDroppingThem() {
        var chunks = new HashMap<String, String>();
        for (int i = 0; i < 2501; i++) {
            var text = new StringBuilder();
            for (int j = 0; j < 100; j++) { text.append('t').append(i * 100 + j).append(' '); }
            chunks.put("c" + i, text.toString());
        }
        assertThatThrownBy(() -> new KeywordIndex(chunks)).hasMessage("KEYWORD_INDEX_LIMIT");
    }

    @Test
    void rejectsTooManyPostingsEvenWhenVocabularyFits() {
        var chunks = new HashMap<String, String>();
        String terms = java.util.stream.IntStream.range(0, 501).mapToObj(i -> "term" + i)
                .collect(java.util.stream.Collectors.joining(" "));
        for (int i = 0; i < 1000; i++) { chunks.put("c" + i, terms); }
        assertThatThrownBy(() -> new KeywordIndex(chunks)).hasMessage("KEYWORD_INDEX_LIMIT");
    }
}
