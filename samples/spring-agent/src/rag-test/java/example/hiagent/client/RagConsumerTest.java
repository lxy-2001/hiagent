package example.hiagent.client;
import com.agentflow.core.*;
import com.agentflow.core.model.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.rag.*;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.core.tool.*;
import com.agentflow.rag.corpus.CorpusSnapshotStore;
import com.agentflow.rag.qdrant.VectorIndex;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class RagConsumerTest {
    @Test void customRetrieverRunsWithoutLlmJarOrVectorConnections() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        RagRetriever retriever = new RagRetriever() {
            public List<RagDocument> retrieve(String query, int limit) { return List.of(); }
            public boolean supportsEvidence() { return true; }
            public RetrievalPayload retrieve(RetrievalRequest request, ToolExecutionControl control) {
                calls.incrementAndGet();
                return new RetrievalPayload("a".repeat(64), RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK,
                        RetrievalPayload.KeywordState.OK, null, RetrievalPayload.EmptyReason.NO_MATCH, false, 0, List.of());
            }
        };
        runner().withPropertyValues("agentflow.rag.enabled=true").withBean(RagRetriever.class, () -> retriever)
                .withBean(ToolExecutionPolicy.class, () -> ToolExecutionPolicy.rules(Map.of("knowledge.search", new ToolPolicyDecision(
                        ToolPolicyDecision.Action.ALLOW, RiskLevel.LOW, ToolPolicyDecision.Effect.READ_ONLY, "Search", Set.of()))))
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(VectorIndex.class).doesNotHaveBean(CorpusSnapshotStore.class);
                    assertThat(context.getBean(RagRetriever.class)).isSameAs(retriever);
                    assertThat(calls).hasValue(0);
                    var result = context.getBean(AgentRuntime.class).run(new AgentRequest("r", "s", "u", "hello"), e -> {});
                    assertThat(result.finalAnswer()).isEqualTo("No evidence");
                    assertThat(calls).hasValue(1);
                    assertThatThrownBy(() -> Class.forName("com.agentflow.llm.AgentFlowProperties")).isInstanceOf(ClassNotFoundException.class);
                });
    }
    @Test void defaultRetrieverWithoutModelConfigurationFails() {
        runner().withPropertyValues("agentflow.rag.enabled=true").run(context -> assertThat(context).hasFailed());
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ClientApplication.class).withBean(AgentModelClient.class,
                () -> request -> request.iteration() == 1
                        ? new ToolCallDecision("d", new ToolCall("c", "knowledge.search", new ToolArguments(Map.of("query", "Java"))), TokenUsage.empty())
                        : new FinalAnswerDecision("f", "No evidence", TokenUsage.empty()));
    }
}
