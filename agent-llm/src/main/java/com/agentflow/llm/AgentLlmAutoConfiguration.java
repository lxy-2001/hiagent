package com.agentflow.llm;

import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.EmbeddingClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.web.client.RestClient")
@AutoConfigureAfter(name = "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration")
@EnableConfigurationProperties(AgentFlowProperties.class)
public class AgentLlmAutoConfiguration {

    @Bean
    @ConditionalOnBean(RestClient.Builder.class)
    @Conditional(AnyModelPortMissingCondition.class)
    @ConditionalOnMissingBean(OpenAiCompatibleModelClient.class)
    OpenAiCompatibleModelClient openAiCompatibleModelClient(
            AgentFlowProperties properties, RestClient.Builder builder) {
        return new OpenAiCompatibleModelClient(properties, builder);
    }

    @Bean
    @ConditionalOnBean(OpenAiCompatibleModelClient.class)
    @ConditionalOnMissingBean(AgentModelClient.class)
    OpenAiAgentModelClient agentModelClient(OpenAiCompatibleModelClient transport) {
        return new OpenAiAgentModelClient(transport);
    }

    @Bean
    @ConditionalOnBean(OpenAiCompatibleModelClient.class)
    @ConditionalOnMissingBean(ChatModelClient.class)
    OpenAiChatModelClient chatModelClient(OpenAiCompatibleModelClient transport) {
        return new OpenAiChatModelClient(transport);
    }

    @Bean
    @ConditionalOnBean(OpenAiCompatibleModelClient.class)
    @ConditionalOnMissingBean(EmbeddingClient.class)
    OpenAiEmbeddingClient embeddingClient(OpenAiCompatibleModelClient transport) {
        return new OpenAiEmbeddingClient(transport);
    }

    static class AnyModelPortMissingCondition extends AnyNestedCondition {

        AnyModelPortMissingCondition() {
            super(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnMissingBean(AgentModelClient.class)
        static class AgentModelPortMissing {
        }

        @ConditionalOnMissingBean(ChatModelClient.class)
        static class ChatModelPortMissing {
        }

        @ConditionalOnMissingBean(EmbeddingClient.class)
        static class EmbeddingModelPortMissing {
        }
    }
}
