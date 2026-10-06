package com.agentflow.llm;

import com.agentflow.core.context.*;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.llm.http.BoundedResponsesTransport;
import tools.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

public final class OpenAiResponsesAgentModelClient implements AgentModelClient {
    public record Counters(Long inputTokens,Long outputTokens,Long cachedInputTokens,Long reasoningTokens) { }
    public record CallReport(HistoryStore.Scope scope,String requestKey,String purpose,String model,String status,
                             Counters counters,String failureCode,Instant startedAt,Instant finishedAt) { }
    private final BoundedResponsesTransport transport;
    private final ObjectMapper mapper;
    private final ResponsesContinuationStore continuations;
    private final Function<AgentModelRequest,HistoryStore.Io> ioResolver;
    private final Consumer<CallReport> observer;
    public OpenAiResponsesAgentModelClient(BoundedResponsesTransport transport,ObjectMapper mapper,
            ResponsesContinuationStore continuations,Function<AgentModelRequest,HistoryStore.Io> ioResolver,
            Consumer<CallReport> observer) {
        this.transport=Objects.requireNonNull(transport);this.mapper=Objects.requireNonNull(mapper);
        this.continuations=Objects.requireNonNull(continuations);this.ioResolver=Objects.requireNonNull(ioResolver);this.observer=Objects.requireNonNull(observer);
    }
    public ModelDecision decide(AgentModelRequest request){
        var scope=new HistoryStore.Scope(request.userId(),request.sessionId(),request.taskId());
        var privateScope=new ResponsesContinuationStore.Scope(scope.userId(),scope.sessionId(),scope.runId(),"openai-responses",transport.config().model());
        String key="decision:"+scope.runId()+":"+request.iteration();Instant started=Instant.now();
        Counters counters=new Counters(null,null,null,null);String status="FAILED",failureCode=null;
        try{
            var io=ioResolver.apply(request);BoundedResponsesTransport.check(io);
            Map<String,Object> body=baseBody(transport.config());
            int limit=request.maxCompletionTokens()==null?transport.config().maxOutputTokens():Math.min(request.maxCompletionTokens(),transport.config().maxOutputTokens());
            if(limit<1)throw new ModelClientException("OUTPUT_BUDGET_EXCEEDED","Responses output budget exhausted");
            body.put("max_output_tokens",limit);
            body.put("input",input(request.messages(),privateScope,io));
            body.put("tools",request.tools().stream().map(OpenAiResponsesAgentModelClient::tool).toList());
            if(!request.tools().isEmpty())body.put("tool_choice","auto");
            JsonNode response=transport.execute(body,io);
            counters=ResponsesDecisionMapper.counters(response);
            var parsed=ResponsesDecisionMapper.parse(response,request.tools(),mapper);
            BoundedResponsesTransport.check(io);
            // Staging never authorizes a tool. History confirms this private reference before execution.
            String ref=continuations.stage(privateScope,key,parsed.replayOutput(),counters.reasoningTokens(),io);
            status="COMPLETED";
            return new AssistantTurnDecision(parsed.decisionId(),parsed.orderedItems(),parsed.nextAction(),parsed.usage(),parsed.usageSource(),parsed.usageDetails(),ref);
        }catch(ModelClientException failure){counters=ResponsesDecisionMapper.failureCounters(failure,counters);failureCode=failure.code();if(Set.of("CANCELLED","TIMED_OUT").contains(failure.code())){status=failure.code();throw new ContextSessionException(failure.code(),failure);}throw failure;}
        catch(ContextSessionException storage){failureCode=storage.code();throw storage;}
        catch(RuntimeException failure){failureCode=ModelClientException.MALFORMED_MODEL_RESPONSE;throw new ModelClientException(failureCode,"Invalid Responses decision",failure);}
        finally{observer.accept(new CallReport(scope,key,"MODEL_DECISION",transport.config().model(),status,counters,failureCode,started,Instant.now()));}
    }
    private List<Object> input(List<ModelMessage> messages,ResponsesContinuationStore.Scope scope,HistoryStore.Io io){
        List<Object> input=new ArrayList<>();Set<String> replayed=new HashSet<>();
        for(var message:messages){
            String ref=message.adapterStateRef();
            if(ref!=null){
                if(replayed.contains(ref))continue;
                Optional<JsonNode> old=continuations.read(scope,ref,io);
                if(old.isPresent()){
                    if(!old.get().isArray())throw new ModelClientException("CONTINUATION_UNAVAILABLE","Responses continuation is invalid");
                    for(JsonNode item:old.get())input.add(item);replayed.add(ref);continue;
                }
                // A deliberate provider/model switch projects the same visible facts without foreign opaque state.
            }
            input.add(message(message,mapper));
        }
        return input;
    }
    static Map<String,Object> baseBody(BoundedResponsesTransport.Config config){
        Map<String,Object> body=new LinkedHashMap<>();body.put("model",config.model());body.put("store",false);
        body.put("parallel_tool_calls",false);body.put("max_output_tokens",config.maxOutputTokens());
        body.put("include",List.of("reasoning.encrypted_content"));body.put("truncation","disabled");return body;
    }
    static Map<String,Object> message(ModelMessage message,ObjectMapper mapper){
        if(message.toolCall()!=null){var call=message.toolCall();return Map.of("type","function_call","call_id",call.callId(),"name",ProviderToolNames.encode(call.name()),"arguments",mapper.writeValueAsString(call.arguments().values()));}
        if(message.role().equals("tool")){
            if(message.toolCallId()==null)throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR,"Tool result has no call id");
            return Map.of("type","function_call_output","call_id",message.toolCallId(),"output",message.content());
        }
        return Map.of("role",message.role(),"content",message.content());
    }
    static Map<String,Object> tool(ToolDefinition definition){
        return Map.of("type","function","name",ProviderToolNames.encode(definition.name()),"description",definition.description(),"parameters",ProviderDecisionMapper.schemaMap(definition.schema()),"strict",false);
    }
}
