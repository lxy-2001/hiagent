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
    @ConditionalOnMissingBean(OpenAiCompatibleModelClient.class)
    OpenAiCompatibleModelClient openAiCompatibleModelClient(
            AgentFlowProperties properties, RestClient.Builder builder) {
        return new OpenAiCompatibleModelClient(properties, builder);
    }

    @Bean
    @ConditionalOnMissingBean(AgentModelClient.class)
    OpenAiAgentModelClient agentModelClient(OpenAiCompatibleModelClient transport) {
        return new OpenAiAgentModelClient(transport);
    }

    @Bean
    @ConditionalOnMissingBean(ChatModelClient.class)
    OpenAiChatModelClient chatModelClient(OpenAiCompatibleModelClient transport) {
        return new OpenAiChatModelClient(transport);
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    OpenAiEmbeddingClient embeddingClient(OpenAiCompatibleModelClient transport) {
        return new OpenAiEmbeddingClient(transport);
    }
}
