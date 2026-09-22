package com.agentflow.rag.corpus;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class StoredNormalizationTest {
    @Test void normalizedExpansionDoesNotReapplyRawInputFileLimit() {
        var chunker=new DocumentChunker();
        var original=chunker.read("unicode.txt","\u0344x".repeat(220000).getBytes(StandardCharsets.UTF_8),800,100);
        byte[] normalized=original.text().getBytes(StandardCharsets.UTF_8);
        assertThat(normalized.length).isGreaterThan(1048576);
        assertThat(chunker.readStored("unicode.txt",normalized,800,100)).isEqualTo(original);
        assertThatThrownBy(() -> chunker.read("unicode.txt",normalized,800,100)).hasMessage("CORPUS_LIMIT");
    }
}
