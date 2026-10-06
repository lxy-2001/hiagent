package com.agentflow.llm;

import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.EmbeddingClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.chat.TokenUsage;
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
            assertThat(context).hasSingleBean(com.agentflow.core.model.DeadlineAwareEmbeddingClient.class);
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
        AgentModelClient custom = request -> new FinalAnswerDecision("custom-decision", "custom", TokenUsage.empty());

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
        AgentModelClient agentModel = request -> new FinalAnswerDecision("custom-decision", "custom-agent", TokenUsage.empty());
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
        AgentModelClient agentModel = request -> new FinalAnswerDecision("custom-decision", "custom-agent", TokenUsage.empty());
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

    @Test
    void harnessCreatesResponsesPortsAndConfiguredEstimator() {
        harnessRunner().withPropertyValues(
                "agentflow.harness.model.name=fixture-responses",
                "agentflow.harness.model.base-url=http://localhost:43210/v1",
                "agentflow.harness.model.request-timeout=17s",
                "agentflow.harness.context.output-tokens=16384")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentModelClient.class);
                    assertThat(context).getBean(AgentModelClient.class).isInstanceOf(OpenAiResponsesAgentModelClient.class);
                    assertThat(context).hasSingleBean(com.agentflow.core.context.CompactionModel.class);
                    assertThat(context).getBean(com.agentflow.core.context.CompactionModel.class).isInstanceOf(ResponsesCompactionModel.class);
                    assertThat(context).hasSingleBean(com.agentflow.core.context.TokenEstimator.class);
                    assertThat(context).getBean(com.agentflow.core.context.TokenEstimator.class).isInstanceOf(JtokkitTokenEstimator.class);
                    var limits=context.getBean(com.agentflow.llm.http.BoundedResponsesTransport.class).config();
                    assertThat(limits.model()).isEqualTo("fixture-responses");
                    assertThat(limits.baseUrl()).isEqualTo("http://localhost:43210/v1");
                    assertThat(limits.timeout()).isEqualTo(java.time.Duration.ofSeconds(17));
                    assertThat(limits.maxOutputTokens()).isEqualTo(16384);
                    assertThat(context).hasSingleBean(ChatModelClient.class);
                    assertThat(context).hasSingleBean(EmbeddingClient.class);
                });
    }

    @Test
    void harnessOverridesBackOffWithoutRequiringUnusedTransportCredentials() {
        AgentModelClient model=request -> new FinalAnswerDecision("custom-decision", "custom", TokenUsage.empty());
        com.agentflow.core.context.CompactionModel compactor=request -> null;
        com.agentflow.core.context.TokenEstimator estimator=new com.agentflow.core.context.TokenEstimator() {
            public long estimateInput(java.util.List<com.agentflow.core.model.ModelMessage> messages,
                                      java.util.List<com.agentflow.core.tool.ToolDefinition> tools) { return 1; }
            public String version() { return "custom"; }
        };
        runner().withPropertyValues("agentflow.harness.enabled=true")
                .withBean(AgentModelClient.class, () -> model)
                .withBean(com.agentflow.core.context.CompactionModel.class, () -> compactor)
                .withBean(com.agentflow.core.context.TokenEstimator.class, () -> estimator)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(AgentModelClient.class).isSameAs(model);
                    assertThat(context).getBean(com.agentflow.core.context.CompactionModel.class).isSameAs(compactor);
                    assertThat(context).getBean(com.agentflow.core.context.TokenEstimator.class).isSameAs(estimator);
                    assertThat(context).doesNotHaveBean(com.agentflow.llm.http.BoundedResponsesTransport.class);
                });
    }

    @Test
    void harnessRejectsMissingRunSupportUnknownEncodingAndUnsupportedProtocol() {
        runner().withPropertyValues("agentflow.harness.enabled=true", "agentflow.harness.model.api-key=fixture")
                .run(context -> assertThat(context).hasFailed());
        harnessRunner().withPropertyValues("agentflow.harness.context.encoding=unsupported-encoding")
                .run(context -> assertThat(context).hasFailed());
        harnessRunner().withPropertyValues("agentflow.harness.model.protocol=chat-completions")
                .run(context -> assertThat(context).hasFailed());
        harnessRunner().withPropertyValues("agentflow.harness.model.api-key=")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner harnessRunner() {
        return runner().withPropertyValues("agentflow.harness.enabled=true", "agentflow.harness.model.api-key=fixture")
                .withBean(ResponsesRunSupport.class, () -> new ResponsesRunSupport() {
                    public com.agentflow.core.context.HistoryStore.Io io(com.agentflow.core.model.AgentModelRequest request) {
                        return new com.agentflow.core.context.HistoryStore.Io(com.agentflow.core.cancel.CancellationSignal.NONE,
                                java.time.Instant.now().plusSeconds(10));
                    }
                    public void record(OpenAiResponsesAgentModelClient.CallReport report) { }
                })
                .withBean(ResponsesContinuationStore.class, () -> new ResponsesContinuationStore() {
                    public String stage(Scope scope,String key,tools.jackson.databind.JsonNode output,Long hint,
                                        com.agentflow.core.context.HistoryStore.Io io) { return "fixture-ref"; }
                    public java.util.Optional<tools.jackson.databind.JsonNode> read(Scope scope,String ref,
                                        com.agentflow.core.context.HistoryStore.Io io) { return java.util.Optional.empty(); }
                    public void prune(Scope scope,com.agentflow.core.context.HistoryStore.Io io) { }
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentLlmAutoConfiguration.class))
                .withBean(RestClient.Builder.class, RestClient::builder);
    }
}
