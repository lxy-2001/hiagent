package com.agentflow.demo;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.*;
import com.agentflow.core.rag.*;
import com.agentflow.core.tool.*;
import com.agentflow.web.auth.JwtService;
import com.agentflow.web.run.RunCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentFlowDemoApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:rag-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true", "agentflow.rag.enabled=true",
        "agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32"})
@org.springframework.context.annotation.Import(RagConversationEndToEndTest.RetrievalFixture.class)
@org.springframework.test.context.ActiveProfiles("rag-e2e")
class RagConversationEndToEndTest {
    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Autowired RunCoordinator coordinator;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AgentModelClient model;
    @Autowired RagRetriever retriever;
    @MockitoBean StringRedisTemplate redis;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final List<AgentModelRequest> requests = new CopyOnWriteArrayList<>();
    private String token;

    @BeforeEach void setup() throws Exception {
        reset(retriever);
        requests.clear();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.hasKey(anyString())).thenReturn(false);
        token = jwt.issueAccessToken("rag-owner", "owner", List.of("USER")).value();
        when(retriever.supportsEvidence()).thenReturn(true);
        when(retriever.retrieve(any(RetrievalRequest.class), any())).thenReturn(payload());
        when(model.decide(any())).thenAnswer(invocation -> {
            AgentModelRequest request = invocation.getArgument(0);
            requests.add(request);
            if (request.input().equals("no evidence")) return new FinalAnswerDecision("d", "No evidence available.", TokenUsage.empty());
            if (request.input().equals("forged")) return new FinalAnswerDecision("d", "Invented [S99]", TokenUsage.empty());
            if (request.input().equals("reuse history")) return new FinalAnswerDecision("d", "Old source [S1]", TokenUsage.empty());
            if (request.iteration() == 1) return new ToolCallDecision("d1", new ToolCall("c", "knowledge.search",
                    new ToolArguments(Map.of("query", "constructor"))), TokenUsage.empty());
            return new FinalAnswerDecision("d2", "Use constructor injection [S1].", TokenUsage.empty());
        });
    }

    @Test void durableEvidenceSurvivesRetrieverChangesAndHistoryCannotReuseIt() throws Exception {
        var accepted = create(Map.of("input", "Java architecture", "requireEvidence", true));
        String task = accepted.path("taskId").asText();
        var result = awaitResult(task, "SUCCEEDED");
        assertThat(result.path("requireEvidence").asBoolean()).isTrue();
        assertThat(result.path("citations").size()).isOne();
        assertThat(result.path("citations").get(0).path("excerpt").asText()).isEqualTo("Constructor injection declares dependencies.");
        assertThat(send("/api/agent/tasks/" + task + "/cancel", "POST", null).body()).contains("Constructor injection");
        when(retriever.retrieve(any(RetrievalRequest.class), any())).thenThrow(new IllegalStateException("index removed"));
        assertThat(json.readTree(send("/api/agent/tasks/" + task, "GET", null).body()).path("citations")).isEqualTo(result.path("citations"));
        var replay = send("/api/agent/tasks/" + task + "/events", "GET", null);
        assertThat(replay.body()).contains("CITATION_VALIDATION", "RUN_TERMINATED").doesNotContain("Constructor injection declares dependencies.");
        var reconnect = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/agent/tasks/" + task + "/events"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).header("Last-Event-ID", "1").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(reconnect.body()).contains("RUN_TERMINATED").doesNotContain("id:1\n");
        var continuation = create(Map.of("input", "reuse history", "sessionId", accepted.path("sessionId").asText()));
        var rejected = awaitResult(continuation.path("taskId").asText(), "FAILED");
        assertThat(rejected.path("terminationReason").asText()).isEqualTo("CITATION_INVALID");
        assertThat(requests.get(requests.size()-1).messages()).extracting(ModelMessage::content)
                .anyMatch(text -> text.contains("[prior-run-source:1]"));
        assertThat(jdbc.queryForObject("select final_answer from agent_task where id=?", String.class, task)).contains("[S1]");
        jdbc.update("update agent_task set citations_json=? where id=?", "x".repeat(65537), task);
        var corrupted = send("/api/agent/tasks/" + task, "GET", null);
        assertThat(corrupted.statusCode()).isEqualTo(503);
        assertThat(corrupted.body()).contains("CITATION_DATA_UNAVAILABLE");
        String ownerToken = token;
        token = jwt.issueAccessToken("stranger", "stranger", List.of("USER")).value();
        assertThat(send("/api/agent/tasks/" + task, "GET", null).statusCode()).isEqualTo(404);
        token = ownerToken;
    }

    @Test void requiredMissingAndForgedEvidenceFailWithoutSuccessfulAnswer() throws Exception {
        for (String input : List.of("no evidence", "forged")) {
            var accepted = create(Map.of("input", input, "requireEvidence", true));
            var result = awaitResult(accepted.path("taskId").asText(), "FAILED");
            assertThat(result.path("terminationReason").asText()).isEqualTo(input.equals("forged") ? "CITATION_INVALID" : "INSUFFICIENT_EVIDENCE");
            assertThat(result.path("finalAnswer").isNull()).isTrue();
            assertThat(result.path("citations").isEmpty()).isTrue();
        }
    }

    @Test void cancellationPropagatesThroughTheActualKnowledgeTool() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        when(retriever.retrieve(any(RetrievalRequest.class), any())).thenAnswer(invocation -> {
            com.agentflow.core.runtime.ToolExecutionControl control = invocation.getArgument(1);
            entered.countDown();
            while (true) { control.checkActive(); Thread.sleep(10); }
        });
        var accepted = create(Map.of("input", "cancel retrieval", "requireEvidence", true));
        assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        String task = accepted.path("taskId").asText();
        assertThat(send("/api/agent/tasks/" + task + "/cancel", "POST", null).statusCode()).isIn(200,202);
        assertThat(awaitResult(task,"CANCELLED").path("citations").isEmpty()).isTrue();
    }

    @Test void vectorFailureUsesActualKeywordFallbackAndRetrievalTimeoutIsNotRunTimeout() throws Exception {
        var source=payload().hits().get(0);
        var profile=new com.agentflow.rag.corpus.CorpusManifest.IndexProfile("test","fixed",3,800,100);
        var snapshot=new com.agentflow.rag.corpus.CorpusSnapshot(source.snapshotId(),"fixture",profile,Map.of(source.chunkId(),source));
        var vectors=mock(com.agentflow.rag.qdrant.VectorIndex.class);
        when(vectors.search(anyString(),anyString(),anyList(),anyInt(),anyDouble(),any()))
                .thenThrow(new IllegalStateException("RAG_VECTOR_UNAVAILABLE"));
        var embedding=new DeadlineAwareEmbeddingClient() {
            public List<Double> embed(String text) { throw new AssertionError("bounded overload required"); }
            public List<Double> embed(String text,EmbeddingCallOptions options) { return List.of(1d,2d,3d); }
        };
        var hybrid=new com.agentflow.rag.retrieval.HybridRagRetriever(snapshot,embedding,vectors,true);
        when(retriever.retrieve(any(RetrievalRequest.class),any())).thenAnswer(i -> hybrid.retrieve(i.getArgument(0),i.getArgument(1)));
        var accepted=create(Map.of("input","fallback","requireEvidence",true));
        String task=accepted.path("taskId").asText();
        assertThat(awaitResult(task,"SUCCEEDED").path("citations").size()).isOne();
        assertThat(send("/api/agent/tasks/"+task+"/steps","GET",null).body()).contains("DEGRADED_KEYWORD", "RAG_VECTOR_UNAVAILABLE");
        when(retriever.retrieve(any(RetrievalRequest.class),any())).thenThrow(new IllegalStateException("RAG_TIMEOUT"));
        var timeout=create(Map.of("input","retrieval timeout","requireEvidence",true));
        assertThat(awaitResult(timeout.path("taskId").asText(),"FAILED").path("terminationReason").asText()).isEqualTo("TOOL_ERROR");
    }

    private RetrievalPayload payload() throws Exception {
        String text = "Constructor injection declares dependencies.";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        var chunk = new RetrievedChunk("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), hash, "java.md", "Java",0,text.length(),text);
        return new RetrievalPayload(chunk.snapshotId(), RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK,
                RetrievalPayload.KeywordState.OK,null,RetrievalPayload.EmptyReason.NONE,false,0,List.of(chunk));
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @org.springframework.context.annotation.Profile("rag-e2e")
    static class RetrievalFixture {
        @org.springframework.context.annotation.Bean
        RagRetriever retriever() {
            var fixture=mock(RagRetriever.class);
            when(fixture.supportsEvidence()).thenReturn(true);
            return fixture;
        }
    }
    private JsonNode create(Map<String, ?> body) throws Exception {
        var response = send("/api/agent/tasks", "POST", body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(202);
        return json.readTree(response.body());
    }
    private JsonNode awaitResult(String task, String status) throws Exception {
        for (int i=0;i<250;i++) {
            var response=send("/api/agent/tasks/"+task,"GET",null);
            if (response.statusCode()==200) {
                var value=json.readTree(response.body());
                if (!value.path("finishedAt").isNull() && coordinator.inFlightCount()==0) {
                    assertThat(value.path("status").asText()).withFailMessage(response.body()).isEqualTo(status);
                    return value;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("run did not finish");
    }
    private String base() { return "http://127.0.0.1:"+port; }
    private HttpResponse<String> send(String path,String method,Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(5))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
