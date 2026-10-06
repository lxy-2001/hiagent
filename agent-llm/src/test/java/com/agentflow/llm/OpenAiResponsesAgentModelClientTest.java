package com.agentflow.llm;

import com.agentflow.core.*;
import com.agentflow.core.context.*;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.*;
import com.agentflow.llm.http.BoundedResponsesTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class OpenAiResponsesAgentModelClientTest {
    ObjectMapper mapper=new ObjectMapper(); HttpServer server;
    final Queue<Integer> statuses=new ConcurrentLinkedQueue<>(); final Queue<String> replies=new ConcurrentLinkedQueue<>(); final List<JsonNode> bodies=new CopyOnWriteArrayList<>();
    final List<OpenAiResponsesAgentModelClient.CallReport> reports=new ArrayList<>();
    final Store store=new Store(); final AtomicBoolean cancelled=new AtomicBoolean();
    BoundedResponsesTransport transport; OpenAiResponsesAgentModelClient client;
    final ToolDefinition tool=new ToolDefinition("file.read","Read a file",RiskLevel.LOW,
            new ToolSchema(Map.of("path",ParameterSpec.requiredString()),Set.of("path"),false));
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/responses", exchange->{
            bodies.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            String reply=replies.remove(); byte[] bytes=reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            Integer status = statuses.poll(); exchange.sendResponseHeaders(status == null ? 200 : status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        transport=new BoundedResponsesTransport(new BoundedResponsesTransport.Config("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-only","gpt-6.1-sol",Duration.ofSeconds(5),8*1024*1024,32768),mapper);
        client=new OpenAiResponsesAgentModelClient(transport,mapper,store,r->io(),reports::add);
    }
    @AfterEach void stop(){server.stop(0);}
    HistoryStore.Io io(){return new HistoryStore.Io(cancelled::get,Instant.now().plusSeconds(10));}
    AgentModelRequest request(List<ModelMessage> messages,int iteration){return new AgentModelRequest(new AgentRequest("run","session","owner","hello"),messages,List.of(tool),iteration,10000);}
    @Test void preservesOrderedTextCallIdAndOpaqueContinuationWithoutRemoteHistory() {
        String alias=ProviderToolNames.encode("file.read");
        replies.add("{\"id\":\"r1\",\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"hidden1\",\"summary\":[],\"encrypted_content\":\"opaque1\"},{\"type\":\"message\",\"role\":\"assistant\",\"phase\":\"commentary\",\"content\":[{\"type\":\"output_text\",\"text\":\"Reading source\"}]},{\"type\":\"function_call\",\"id\":\"fc1\",\"call_id\":\"c1\",\"name\":\""+alias+"\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":4,\"input_tokens_details\":{\"cached_tokens\":8},\"output_tokens_details\":{\"reasoning_tokens\":1}}}");
        var turn=(AssistantTurnDecision)client.decide(request(List.of(ModelMessage.user("hello")),1));
        assertThat(turn.nextAction()).isEqualTo(AssistantTurnDecision.NextAction.TOOL_CALL);
        assertThat(turn.visibleText()).isEqualTo("Reading source");
        assertThat(turn.toolCall().name()).isEqualTo("file.read");
        assertThat(turn.toolCall().callId()).isEqualTo("c1");
        assertThat(turn.usage().totalTokens()).isEqualTo(14);
        assertThat(turn.usageDetails().cachedInputTokens()).isEqualTo(8);
        assertThat(turn.continuationRef()).isNotBlank();
        replies.add("{\"id\":\"r2\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"phase\":\"final_answer\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}]}");
        var messages=new ArrayList<ModelMessage>(); messages.add(ModelMessage.user("hello"));
        for(var m:turn.orderedItems()) messages.add(withRef(m,turn.continuationRef()));
        messages.add(new ModelMessage("tool","source", "file.read","c1",null));
        var result=(AssistantTurnDecision)client.decide(request(messages,2));
        assertThat(result.nextAction()).isEqualTo(AssistantTurnDecision.NextAction.FINAL);
        JsonNode sent=bodies.get(1); assertThat(sent.get("store").asBoolean()).isFalse();
        assertThat(sent.get("parallel_tool_calls").asBoolean()).isFalse();
        assertThat(sent.get("max_output_tokens").asInt()).isEqualTo(10000);
        assertThat(sent.has("previous_response_id")).isFalse();
        assertThat(sent.toString()).contains("opaque1","c1","function_call_output","source","commentary");
        assertThat(sent.get("input")).hasSize(5);
        assertThat(reports).hasSize(2);
    }
    // This is the opaque ID only. Its payload remains outside agent-core.
    private ModelMessage withRef(ModelMessage m,String ref){
        try{return (ModelMessage)ModelMessage.class.getMethod("withAdapterStateRef",String.class).invoke(m,ref);}
        catch(ReflectiveOperationException missing){throw new AssertionError("Core must carry opaque reference",missing);}
    }
    @ParameterizedTest @ValueSource(strings={"incomplete","failed","cancelled","queued"})
    void neverTreatsPartialProviderResponsesAsSuccessfulDecisions(String status) {
        replies.add("{\"id\":\"partial\",\"status\":\""+status+"\",\"output\":[],\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}");
        assertThatThrownBy(()->client.decide(request(List.of(ModelMessage.user("hello")),1))).isInstanceOf(ModelClientException.class);
        assertThat(reports).hasSize(1); assertThat(reports.get(0).counters().inputTokens()).isEqualTo(3);
        assertThat(store.values).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"missing","zero","partial"})
    void distinguishesUnknownUsageFromReportedZero(String kind) {
        String usage=switch(kind){case "zero"->",\"usage\":{\"input_tokens\":0,\"output_tokens\":0}";case "partial"->",\"usage\":{\"input_tokens\":4}";default->"";};
        replies.add("{\"id\":\"u\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"answer\"}]}]"+usage+"}");
        var result=(AssistantTurnDecision)client.decide(request(List.of(ModelMessage.user("hello")),1));
        assertThat(result.usageSource()).isEqualTo(kind.equals("zero")?UsageSource.REPORTED:UsageSource.UNKNOWN);
        assertThat(reports.get(0).counters().outputTokens()).isEqualTo(kind.equals("zero")?0L:null);
        assertThat(reports.get(0).counters().inputTokens()).isEqualTo(kind.equals("missing")?null:kind.equals("zero")?0L:4L);
    }
    @ParameterizedTest @ValueSource(strings={"multiple","bad-json","unknown-tool","schema","refusal","incomplete-call"})
    void rejectsInvalidDecisionsBeforeAnyToolCanExecute(String kind) {
        String alias=ProviderToolNames.encode(tool.name());
        String name=kind.equals("unknown-tool")?"unknown":alias;
        String args=kind.equals("bad-json")?"bad":kind.equals("schema")?"{}":"{\\\"path\\\":\\\"a\\\"}";
        String call="{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\""+name+"\",\"arguments\":\""+args+"\"}";
        if(kind.equals("incomplete-call")) call=call.replace("\"type\":\"function_call\"", "\"type\":\"function_call\",\"status\":\"incomplete\"");
        String output=kind.equals("refusal")?"{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}":kind.equals("multiple")?call+","+call:call;
        replies.add("{\"id\":\"bad\",\"status\":\"completed\",\"output\":["+output+"]}");
        assertThatThrownBy(()->client.decide(request(List.of(ModelMessage.user("hello")),1))).isInstanceOf(ModelClientException.class);
        assertThat(store.values).isEmpty();
    }
    @Test void managedToolArgumentsRemainCompleteBeyondLegacyTextLimits() {
        String value = "x".repeat(5000);
        String arguments = mapper.writeValueAsString(Map.of("path", value));
        replies.add(mapper.writeValueAsString(Map.of("id", "long-args", "status", "completed", "output", List.of(
                Map.of("type", "function_call", "call_id", "long-call", "name", ProviderToolNames.encode(tool.name()), "arguments", arguments)))));
        var result = (AssistantTurnDecision) client.decide(request(List.of(ModelMessage.user("hello")), 1));
        assertThat(result.nextAction()).isEqualTo(AssistantTurnDecision.NextAction.TOOL_CALL);
        assertThat(result.toolCall().arguments().values().get("path")).isEqualTo(value);
    }
    @Test void failedHttpResponsesStillReportKnownUsageWithoutStagingAContinuation() {
        statuses.add(500);
        replies.add("{\"error\":{\"message\":\"private provider diagnostic\"},\"usage\":{\"input_tokens\":7,\"output_tokens\":2}}");
        assertThatThrownBy(() -> client.decide(request(List.of(ModelMessage.user("hello")), 1)))
                .isInstanceOf(ModelClientException.class).hasMessageContaining("status 500")
                .hasMessageNotContaining("private provider diagnostic");
        assertThat(store.values).isEmpty();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).counters().inputTokens()).isEqualTo(7);
        assertThat(reports.get(0).counters().outputTokens()).isEqualTo(2);
        assertThat(reports.get(0).status()).isEqualTo("FAILED");
    }
    @Test void laterFinalAnswerCompletesAnEarlierCommentaryMessage() {
        replies.add(mapper.writeValueAsString(Map.of("id", "mixed", "status", "completed", "output", List.of(
                Map.of("type", "message", "role", "assistant", "phase", "commentary", "content", List.of(Map.of("type", "output_text", "text", "Checking source"))),
                Map.of("type", "message", "role", "assistant", "phase", "final_answer", "content", List.of(Map.of("type", "output_text", "text", "done")))))));
        var result = (AssistantTurnDecision) client.decide(request(List.of(ModelMessage.user("hello")), 1));
        assertThat(result.nextAction()).isEqualTo(AssistantTurnDecision.NextAction.FINAL);
        assertThat(result.orderedItems()).extracting(ModelMessage::content).containsExactly("Checking source", "done");
    }
    @Test void invalidSummaryUsageKeepsRawCountersAndNeverThrowsAnOverflowInFailureHandling() {
        replies.add("{\"id\":\"summary-bad-usage\",\"status\":\"incomplete\",\"output\":[],\"usage\":{\"input_tokens\":2147483647,\"output_tokens\":1}}");
        var compactor = new ResponsesCompactionModel(transport, mapper, reports::add);
        var result = compactor.summarize(new CompactionModel.Request(new HistoryStore.Scope("owner", "session", "run"),
                List.of(ModelMessage.user("do work")), "fixed summary prompt", 8192, io(), "bad-summary-key"));
        assertThat(result.completed()).isFalse();
        assertThat(result.usageSource()).isEqualTo(UsageSource.UNKNOWN);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).counters().inputTokens()).isEqualTo(2147483647L);
        assertThat(reports.get(0).counters().outputTokens()).isEqualTo(1L);
    }
    @Test void summaryReservesReasoningBeyondVisibleTargetWithoutToolsAndReportsRealUsage() {
        Map<String,Object> summary=new LinkedHashMap<>(); summary.put("goal","fix");
        for(String key:List.of("constraints","userCorrections","decisions","completedFacts","remainingWork","blockers","uncertainActions","references"))summary.put(key,List.of());
        summary.put("continuationState","continue");
        replies.add(mapper.writeValueAsString(Map.of("id","summary","status","completed","output",List.of(Map.of("type","message","role","assistant","content",List.of(Map.of("type","output_text","text",mapper.writeValueAsString(summary))))),"usage",Map.of("input_tokens",100,"output_tokens",12000,"output_tokens_details",Map.of("reasoning_tokens",9000)))));
        var compactor=new ResponsesCompactionModel(transport,mapper,reports::add);
        var result=compactor.summarize(new CompactionModel.Request(new HistoryStore.Scope("owner","session","run"),List.of(ModelMessage.user("do work")),"fixed summary prompt",8192,io(),"summary-key"));
        assertThat(result.completed()).isTrue(); assertThat(result.summary()).contains("fix");
        assertThat(result.usage().totalTokens()).isEqualTo(12100);
        assertThat(result.usageDetails().reasoningTokens()).isEqualTo(9000);
        assertThat(bodies.get(0).has("tools")).isFalse();
        assertThat(bodies.get(0).get("max_output_tokens").asInt()).isEqualTo(32768);
        assertThat(bodies.get(0).get("text").get("format").get("strict").asBoolean()).isTrue();
        assertThat(reports.get(0).purpose()).isEqualTo("COMPACTION");
    }
    @Test void cancellationAndResponseLimitAreRealBoundaries() {
        cancelled.set(true);
        assertThatThrownBy(()->client.decide(request(List.of(ModelMessage.user("hello")),1))).isInstanceOf(com.agentflow.core.context.ContextSessionException.class).hasMessage("CANCELLED");
        assertThat(bodies).isEmpty(); cancelled.set(false);
        replies.add(" ".repeat(8*1024*1024+1));
        assertThatThrownBy(()->client.decide(request(List.of(ModelMessage.user("hello")),1))).isInstanceOf(ModelClientException.class).hasMessageContaining("limit");
    }
    static class Store implements ResponsesContinuationStore {
        Map<String,JsonNode> values=new HashMap<>();
        public String stage(Scope scope,String key,JsonNode output,Long hint,HistoryStore.Io io){String id=UUID.randomUUID().toString();values.put(id,output.deepCopy());return id;}
        public Optional<JsonNode> read(Scope scope,String id,HistoryStore.Io io){return Optional.ofNullable(values.get(id));}
        public void prune(Scope scope,HistoryStore.Io io) { }
    }
}
