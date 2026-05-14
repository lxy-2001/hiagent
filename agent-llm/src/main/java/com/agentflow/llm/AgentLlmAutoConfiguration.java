package com.agentflow.llm;

import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.EmbeddingClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@EnableConfigurationProperties(AgentFlowProperties.class)
public class AgentLlmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenAiCompatibleModelClient openAiCompatibleModelClient(AgentFlowProperties properties, RestClient.Builder builder) {
        return new OpenAiCompatibleModelClient(properties, builder);
    }

    @Bean
    @ConditionalOnMissingBean(AgentModelClient.class)
    AgentModelClient agentModelClient(OpenAiCompatibleModelClient client) {
        return client;
    }

    @Bean
    @ConditionalOnMissingBean(ChatModelClient.class)
    ChatModelClient chatModelClient(OpenAiCompatibleModelClient client) {
        return client;
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    EmbeddingClient embeddingClient(OpenAiCompatibleModelClient client) {
        return client;
    }
}
