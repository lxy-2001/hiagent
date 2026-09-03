package com.agentflow.llm;

import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLlmAutoConfigurationTest {

    @Test
    void createsAllThreePortsWhenApplicationProvidesNoOverride() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(AgentModelClient.class);
            assertThat(context).hasSingleBean(ChatModelClient.class);
            assertThat(context).hasSingleBean(EmbeddingClient.class);
            assertThat(context.getBean(AgentModelClient.class).getClass().getSimpleName())
                    .isEqualTo("OpenAiAgentModelClient");
            assertThat(context.getBean(ChatModelClient.class).getClass().getSimpleName())
                    .isEqualTo("OpenAiChatModelClient");
            assertThat(context.getBean(EmbeddingClient.class).getClass().getSimpleName())
                    .isEqualTo("OpenAiEmbeddingClient");
        });
    }

    @Test
    void agentModelOverrideDoesNotSuppressOtherPortsOrCreateSharedClient() {
        AgentModelClient custom = prompt -> "custom";

        runner().withBean(AgentModelClient.class, () -> custom).run(context -> {
            assertThat(context).getBean(AgentModelClient.class).isSameAs(custom);
            assertThat(context).hasSingleBean(ChatModelClient.class);
            assertThat(context).hasSingleBean(EmbeddingClient.class);
        });
    }

    @Test
    void chatModelOverrideDoesNotSuppressOtherPortsOrCreateSharedClient() {
        ChatModelClient custom = new ChatModelClient() {
            @Override
            public com.agentflow.core.chat.ChatCompletionResponse complete(
                    com.agentflow.core.chat.ChatCompletionRequest request) {
                return null;
            }

            @Override
            public com.agentflow.core.chat.ChatCompletionResponse stream(
                    com.agentflow.core.chat.ChatCompletionRequest request,
                    java.util.function.Consumer<String> deltaConsumer) {
                return null;
            }
        };

        runner().withBean(ChatModelClient.class, () -> custom).run(context -> {
            assertThat(context).getBean(ChatModelClient.class).isSameAs(custom);
            assertThat(context).hasSingleBean(AgentModelClient.class);
            assertThat(context).hasSingleBean(EmbeddingClient.class);
        });
    }

    @Test
    void embeddingOverrideDoesNotSuppressOtherPortsOrCreateSharedClient() {
        EmbeddingClient custom = text -> java.util.List.of(1.0d);

        runner().withBean(EmbeddingClient.class, () -> custom).run(context -> {
            assertThat(context).getBean(EmbeddingClient.class).isSameAs(custom);
            assertThat(context).hasSingleBean(AgentModelClient.class);
            assertThat(context).hasSingleBean(ChatModelClient.class);
        });
    }

    @Test
    void allApplicationModelOverridesDoNotCreateTransportWhenBuilderAvailable() {
        AgentModelClient agentModel = prompt -> "custom-agent";
        ChatModelClient chatModel = new ChatModelClient() {
            @Override
            public com.agentflow.core.chat.ChatCompletionResponse complete(
                    com.agentflow.core.chat.ChatCompletionRequest request) {
                return null;
            }

            @Override
            public com.agentflow.core.chat.ChatCompletionResponse stream(
                    com.agentflow.core.chat.ChatCompletionRequest request,
                    java.util.function.Consumer<String> deltaConsumer) {
                return null;
            }
        };
        EmbeddingClient embedding = text -> java.util.List.of(1.0d);

        runner()
                .withBean(AgentModelClient.class, () -> agentModel)
                .withBean(ChatModelClient.class, () -> chatModel)
                .withBean(EmbeddingClient.class, () -> embedding)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(AgentModelClient.class).isSameAs(agentModel);
                    assertThat(context).getBean(ChatModelClient.class).isSameAs(chatModel);
                    assertThat(context).getBean(EmbeddingClient.class).isSameAs(embedding);
                    assertThat(context).doesNotHaveBean(OpenAiCompatibleModelClient.class);
                });
    }

    @Test
    void allApplicationModelOverridesWorkWithoutOptionalRestClientDependency() {
        AgentModelClient agentModel = prompt -> "custom-agent";
        ChatModelClient chatModel = new ChatModelClient() {
            @Override
            public com.agentflow.core.chat.ChatCompletionResponse complete(
                    com.agentflow.core.chat.ChatCompletionRequest request) {
                return null;
            }

            @Override
            public com.agentflow.core.chat.ChatCompletionResponse stream(
                    com.agentflow.core.chat.ChatCompletionRequest request,
                    java.util.function.Consumer<String> deltaConsumer) {
                return null;
            }
        };
        EmbeddingClient embedding = text -> java.util.List.of(1.0d);

        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("org.springframework.web.client"))
                .withConfiguration(AutoConfigurations.of(AgentLlmAutoConfiguration.class))
                .withBean(AgentModelClient.class, () -> agentModel)
                .withBean(ChatModelClient.class, () -> chatModel)
                .withBean(EmbeddingClient.class, () -> embedding)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(AgentModelClient.class).isSameAs(agentModel);
                    assertThat(context).getBean(ChatModelClient.class).isSameAs(chatModel);
                    assertThat(context).getBean(EmbeddingClient.class).isSameAs(embedding);
                    assertThat(context).doesNotHaveBean(OpenAiCompatibleModelClient.class);
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentLlmAutoConfiguration.class))
                .withBean(RestClient.Builder.class, RestClient::builder);
    }
}
