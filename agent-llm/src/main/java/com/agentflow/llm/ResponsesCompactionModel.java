package com.agentflow.llm;

import com.agentflow.core.context.*;
import com.agentflow.core.model.*;
import com.agentflow.llm.http.BoundedResponsesTransport;
import tools.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Dedicated no-tool summary request with the same bounded transport and immutable model snapshot. */
public final class ResponsesCompactionModel implements CompactionModel {
    private final BoundedResponsesTransport transport;private final ObjectMapper mapper;
    private final Consumer<OpenAiResponsesAgentModelClient.CallReport> observer;
    public ResponsesCompactionModel(BoundedResponsesTransport transport,ObjectMapper mapper,Consumer<OpenAiResponsesAgentModelClient.CallReport> observer){
        this.transport=Objects.requireNonNull(transport);this.mapper=Objects.requireNonNull(mapper);this.observer=Objects.requireNonNull(observer);
    }
    public Result summarize(Request request){
        Instant started=Instant.now();String status="FAILED",failureCode=null;
        var counters=new OpenAiResponsesAgentModelClient.Counters(null,null,null,null);
        try{
            BoundedResponsesTransport.check(request.io());
            Map<String,Object> body=OpenAiResponsesAgentModelClient.baseBody(transport.config());
            body.put("max_output_tokens", Math.min(request.outputTokens(), transport.config().maxOutputTokens()));
            List<Object> input=new ArrayList<>();input.add(Map.of("role","developer","content",request.instruction()));
            // The source is quoted data, never live tool calls. Summarization cannot execute tools.
            List<Object> source=request.source().stream().map(m->(Object)Map.of("role",m.role(),"content",m.content(),"toolCallId",Objects.requireNonNullElse(m.toolCallId(),""),"tool",m.toolCall()==null?Map.of():Map.of("name",m.toolCall().name(),"arguments",m.toolCall().arguments().values()))).toList();
            input.add(Map.of("role","user","content",mapper.writeValueAsString(source)));body.put("input",input);
            body.put("text",Map.of("format",Map.of("type","json_schema","name","context_summary_v1","strict",true,"schema",summarySchema())));
            JsonNode response=transport.execute(body,request.io());counters=ResponsesDecisionMapper.counters(response);
            var parsed=ResponsesDecisionMapper.parse(response,List.of(),mapper);
            String summary=parsed.orderedItems().stream().map(ModelMessage::content).collect(java.util.stream.Collectors.joining("\n"));
            JsonNode fields=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(summary);
            if(fields==null||!fields.isObject()||!CompactionPrompt.validFields(mapper.convertValue(fields,Map.class)))throw ResponsesDecisionMapper.invalid("Summary JSON fields are invalid");
            BoundedResponsesTransport.check(request.io());status="COMPLETED";
            return new Result(true,mapper.writeValueAsString(fields),parsed.usage(),parsed.usageSource(),parsed.usageDetails());
        }catch(ModelClientException failure){counters=ResponsesDecisionMapper.failureCounters(failure,counters);failureCode=failure.code();if(Set.of("CANCELLED","TIMED_OUT").contains(failure.code())){status=failure.code();throw new ContextSessionException(failure.code(),failure);}return new Result(false,"",usage(counters),source(counters),new AssistantTurnDecision.UsageDetails(counters.cachedInputTokens(),counters.reasoningTokens()));}
        catch(ContextSessionException failure){failureCode=failure.code();status=failure.code();throw failure;}
        catch(RuntimeException failure){failureCode="INVALID_COMPACTION_RESPONSE";return new Result(false,"",usage(counters),source(counters),new AssistantTurnDecision.UsageDetails(counters.cachedInputTokens(),counters.reasoningTokens()));}
        finally{observer.accept(new OpenAiResponsesAgentModelClient.CallReport(request.scope(),request.requestKey(),"COMPACTION",transport.config().model(),status,counters,failureCode,started,Instant.now()));}
    }
    private static com.agentflow.core.chat.TokenUsage usage(OpenAiResponsesAgentModelClient.Counters counters){
        int a=counters.inputTokens()==null?0:Math.toIntExact(counters.inputTokens()),b=counters.outputTokens()==null?0:Math.toIntExact(counters.outputTokens());
        long total=(long)a+b;
        return total>Integer.MAX_VALUE?com.agentflow.core.chat.TokenUsage.empty():new com.agentflow.core.chat.TokenUsage(a,b,(int)total);
    }
    private static UsageSource source(OpenAiResponsesAgentModelClient.Counters counters){return counters.inputTokens()!=null&&counters.outputTokens()!=null&&counters.inputTokens()+counters.outputTokens()<=Integer.MAX_VALUE?UsageSource.REPORTED:UsageSource.UNKNOWN;}
    static Map<String,Object> summarySchema(){
        Map<String,Object> properties=new LinkedHashMap<>();properties.put("goal",Map.of("type","string"));
        for(String field:List.of("constraints","userCorrections","decisions","completedFacts","remainingWork","blockers","uncertainActions"))properties.put(field,Map.of("type","array","items",Map.of("type","string")));
        properties.put("references",Map.of("type","array","items",Map.of("type","object","additionalProperties",false,"required",List.of("kind","id","availability","note"),"properties",Map.of("kind",Map.of("type","string","enum",List.of("FILE","ARTIFACT","SYMBOL")),"id",Map.of("type","string"),"availability",Map.of("type","string","enum",List.of("AVAILABLE","TRUNCATED","EVICTED","MISSING","UNKNOWN")),"note",Map.of("type","string")))));
        properties.put("continuationState",Map.of("type","string"));
        return Map.of("type","object","properties",properties,"required",List.copyOf(properties.keySet()),"additionalProperties",false);
    }
}
