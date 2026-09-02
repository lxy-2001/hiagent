package com.agentflow.rag;

import com.agentflow.core.rag.RagDocument;
import com.agentflow.core.rag.RagRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRagAutoConfigurationTest {

    @Test
    void doesNotCreateRagRetrieverWithoutAnApplicationImplementation() {
        runner().run(context -> assertThat(context).doesNotHaveBean(RagRetriever.class));
    }

    @Test
    void keepsApplicationRetrieverAsTheOnlyCandidate() {
        RagRetriever custom = (query, limit) -> List.of(
                new RagDocument("doc-1", "test", "content", 1.0d));

        runner().withBean(RagRetriever.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(RagRetriever.class);
            assertThat(context).getBean(RagRetriever.class).isSameAs(custom);
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRagAutoConfiguration.class));
    }
}
