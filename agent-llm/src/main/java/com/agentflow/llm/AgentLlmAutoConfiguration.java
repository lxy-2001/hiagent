package com.agentflow.llm;

import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.context.CompactionModel;
import com.agentflow.core.context.TokenEstimator;
import com.agentflow.llm.http.BoundedResponsesTransport;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Configuration;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.EmbeddingClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    @ConditionalOnProperty(name = "agentflow.harness.enabled", havingValue = "false", matchIfMissing = true)
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
    OpenAiEmbeddingClient embeddingClient(AgentFlowProperties properties) {
        return new OpenAiEmbeddingClient(properties);
    }

    /** Optional Responses assembly; durable run services remain supplied by the consuming application. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "agentflow.harness.enabled", havingValue = "true")
    static class HarnessResponsesConfiguration {
        @Bean
        @ConditionalOnMissingBean(TokenEstimator.class)
        JtokkitTokenEstimator harnessTokenEstimator(Environment environment) {
            return new JtokkitTokenEstimator(environment.getProperty("agentflow.harness.context.encoding", "o200k_base"));
        }

        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        ObjectMapper harnessResponsesObjectMapper() { return new ObjectMapper(); }

        @Bean
        @Conditional(AnyResponsesPortMissingCondition.class)
        @ConditionalOnMissingBean(BoundedResponsesTransport.class)
        BoundedResponsesTransport harnessResponsesTransport(Environment environment, ObjectMapper mapper) {
            if (!"responses".equals(environment.getProperty("agentflow.harness.model.protocol", "responses"))) {
                throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR,
                        "Harness model protocol must be responses");
            }
            var limits = new BoundedResponsesTransport.Config(
                    environment.getProperty("agentflow.harness.model.base-url", "https://api.openai.com/v1"),
                    environment.getProperty("agentflow.harness.model.api-key"),
                    environment.getProperty("agentflow.harness.model.name", "gpt-6.1-sol"),
                    DurationStyle.detectAndParse(environment.getProperty("agentflow.harness.model.request-timeout", "120s")),
                    environment.getProperty("agentflow.harness.model.max-response-bytes", Integer.class, 8388608),
                    environment.getProperty("agentflow.harness.context.output-tokens", Integer.class, 32768));
            return new BoundedResponsesTransport(limits, mapper);
        }

        @Bean
        @ConditionalOnMissingBean(AgentModelClient.class)
        OpenAiResponsesAgentModelClient harnessAgentModelClient(BoundedResponsesTransport transport,
                ObjectMapper mapper, ResponsesContinuationStore continuation, ResponsesRunSupport support) {
            return new OpenAiResponsesAgentModelClient(transport, mapper, continuation, support::io, support::record);
        }

        @Bean
        @ConditionalOnMissingBean(CompactionModel.class)
        ResponsesCompactionModel harnessCompactionModel(BoundedResponsesTransport transport,
                ObjectMapper mapper, ResponsesRunSupport support) {
            return new ResponsesCompactionModel(transport, mapper, support::record);
        }
    }

    static class AnyResponsesPortMissingCondition extends AnyNestedCondition {
        AnyResponsesPortMissingCondition() { super(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN); }
        @ConditionalOnMissingBean(AgentModelClient.class)
        static class AgentModelPortMissing { }
        @ConditionalOnMissingBean(CompactionModel.class)
        static class CompactionModelPortMissing { }
    }

    static class AnyModelPortMissingCondition extends AnyNestedCondition {

        AnyModelPortMissingCondition() {
            super(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnMissingBean(AgentModelClient.class)
        @ConditionalOnProperty(name = "agentflow.harness.enabled", havingValue = "false", matchIfMissing = true)
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
