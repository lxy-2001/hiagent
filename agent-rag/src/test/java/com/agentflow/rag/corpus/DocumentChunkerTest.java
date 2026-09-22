package com.agentflow.rag.corpus;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class DocumentChunkerTest {
    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void canonicalizesEncodingWithoutCollapsingWhitespaceAndKeepsIdentityStable() {
        var a = read("java.md", "\uFEFF# Cafe\u0301\r\n\rJava  \t\n");
        var b = read("java.md", "# Café\n\nJava  \t\n");
        assertThat(a).isEqualTo(b);
        assertThat(a.title()).isEqualTo("Café");
        assertThat(a.text()).isEqualTo("# Café\n\nJava  \t\n");
        assertThat(a.docId()).matches("[a-f0-9]{64}");
        assertThat(a.chunks()).hasSize(1);
        assertThat(read("other.md", a.text()).docId()).isNotEqualTo(a.docId());
        assertThat(read("other.md", a.text()).chunks().get(0).chunkId()).isNotEqualTo(a.chunks().get(0).chunkId());
        assertThat(read("java.md", a.text() + "new").version()).isNotEqualTo(a.version());
    }

    @Test
    void usesCodePointsAndStopsAtTheFirstWindowReachingTheEnd() {
        var document = read("emoji.txt", "😀".repeat(1500));
        assertThat(document.chunks()).hasSize(2);
        assertThat(document.chunks().get(0).start()).isZero();
        assertThat(document.chunks().get(0).end()).isEqualTo(800);
        assertThat(document.chunks().get(1).start()).isEqualTo(700);
        assertThat(document.chunks().get(1).end()).isEqualTo(1500);
        assertThat(document.chunks().get(0).text().length()).isEqualTo(1600);
        assertThat(read("exact.txt", "a".repeat(800)).chunks()).hasSize(1);
        assertThat(read("empty.txt", " \n\t").chunks()).isEmpty();
    }

    @Test
    void rejectsBadEncodingControlsSecretsAndInvalidWindowSizes() {
        assertThatThrownBy(() -> chunker.read("bad.txt", new byte[]{(byte) 0xc3, 0x28}, 800, 100))
                .hasMessage("INVALID_UTF8");
        for (String text : new String[]{"a\u0000b", "token=private-value", "Bearer abc123", "-----BEGIN PRIVATE KEY-----"}) {
            assertThatThrownBy(() -> read("bad.txt", text)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> chunker.read("x.md", new byte[]{65}, 199, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.read("x.md", new byte[]{65}, 200, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.read("x.md", new byte[1_048_577], 800, 100)).hasMessage("CORPUS_LIMIT");
    }

    private DocumentChunker.Document read(String path, String text) {
        return chunker.read(path, text.getBytes(StandardCharsets.UTF_8), 800, 100);
    }

    @Test
    void tupleLengthPrefixesAvoidAmbiguousConcatenation() {
        assertThat(CorpusHash.tuple("test", "ab", "c")).isNotEqualTo(CorpusHash.tuple("test", "a", "bc"));
        assertThat(CorpusHash.content("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
