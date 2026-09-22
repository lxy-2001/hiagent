package com.agentflow.demo;

import com.agentflow.core.rag.RagRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LegacyKnowledgeRetirementTest {
    @Test
    void retiredPackageHasNoImporterOrRetrieverEvenWithOldBootstrapFlag() {
        new ApplicationContextRunner().withUserConfiguration(KnowledgePackage.class)
                .withBean(com.agentflow.core.model.EmbeddingClient.class, () -> org.mockito.Mockito.mock(com.agentflow.core.model.EmbeddingClient.class))
                .withBean(com.agentflow.llm.AgentFlowProperties.class, com.agentflow.llm.AgentFlowProperties::new)
                .withBean(org.springframework.web.client.RestClient.Builder.class, org.springframework.web.client.RestClient::builder)
                .withPropertyValues("agentflow.knowledge.bootstrap.enabled=true", "agentflow.rag.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RagRetriever.class);
                    assertThat(context).doesNotHaveBean("loadBuiltInKnowledge");
                    var mvc = MockMvcBuilders.standaloneSetup(context.getBean(
                            com.agentflow.demo.knowledge.KnowledgeController.class)).build();
                    mvc.perform(post("/api/knowledge/reload"))
                            .andExpect(status().isGone())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(jsonPath("$.code").value("KNOWLEDGE_RELOAD_RETIRED"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.agentflow.demo.knowledge")
    static class KnowledgePackage { }
}
