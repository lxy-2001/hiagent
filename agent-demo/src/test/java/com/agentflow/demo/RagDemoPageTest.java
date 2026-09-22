package com.agentflow.demo;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class RagDemoPageTest {
    @Test void pageOffersEvidenceChoiceAndPlainTextCitationsWithoutOnlineImport() throws Exception {
        String page;
        try (var input = new ClassPathResource("static/index.html").getInputStream()) {
            page = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(page).contains("id=\"requireEvidence\"", "id=\"citations\"", "excerpt.textContent = citation.excerpt",
                "generation === observationGeneration");
        assertThat(page).doesNotContain("/api/knowledge/reload", "innerHTML", "citation.href");
    }
}
