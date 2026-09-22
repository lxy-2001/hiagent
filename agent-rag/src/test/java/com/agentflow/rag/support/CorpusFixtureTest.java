package com.agentflow.rag.support;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusFixtureTest {
    private static final String ROOT = "/corpus/feature005/";

    @Test
    void goldAnswersReferToRealSourceRangesAndVerifiedContents() throws Exception {
        var mapper = new JsonMapper();
        var manifest = mapper.readTree(resource("manifest.json"));
        var questions = mapper.readTree(resource("questions.json"));
        assertThat(manifest.get("documents").size()).isGreaterThanOrEqualTo(20);
        assertThat(questions.get("questions").size()).isGreaterThanOrEqualTo(20);
        var paths = new HashSet<String>();
        for (var document : manifest.get("documents")) {
            var path = document.get("sourcePath").asString();
            assertThat(paths.add(path)).isTrue();
            assertThat(path).doesNotContain("..", "\\").doesNotStartWith("/");
            var content = resource("documents/" + path);
            assertThat(content).isNotBlank().doesNotContain("\r", "\u0000", "sk-", "-----BEGIN PRIVATE KEY");
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(document.get("contentHash").asString());
        }
        var ids = new HashSet<String>();
        for (var question : questions.get("questions")) {
            assertThat(ids.add(question.get("id").asString())).isTrue();
            assertThat(question.get("query").asString()).isNotBlank();
            assertThat(question.get("gold").size()).isPositive();
            for (var gold : question.get("gold")) {
                var path = gold.get("sourcePath").asString();
                assertThat(paths).contains(path);
                var content = resource("documents/" + path);
                int start = gold.get("start").asInt();
                int end = gold.get("end").asInt();
                assertThat(start).isNotNegative();
                assertThat(end).isGreaterThan(start).isLessThanOrEqualTo(content.codePointCount(0, content.length()));
                assertThat(content.substring(content.offsetByCodePoints(0, start), content.offsetByCodePoints(0, end)))
                        .isEqualTo(gold.get("excerpt").asString());
            }
        }
    }

    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream(ROOT + name)) {
            assertThat(input).as(name).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
