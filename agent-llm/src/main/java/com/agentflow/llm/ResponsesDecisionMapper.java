package com.agentflow.llm;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.*;
import tools.jackson.databind.*;
import java.util.*;

/** Strict Responses mapping; no opaque reasoning content is included in visible messages. */
public final class ResponsesDecisionMapper {
    private ResponsesDecisionMapper(){ }
    public record Parsed(String decisionId,List<ModelMessage> orderedItems,AssistantTurnDecision.NextAction nextAction,
                         TokenUsage usage,UsageSource usageSource,AssistantTurnDecision.UsageDetails usageDetails,
                         OpenAiResponsesAgentModelClient.Counters counters,JsonNode replayOutput) { }
    public static OpenAiResponsesAgentModelClient.Counters counters(JsonNode response){
        var usage=response==null?null:response.get("usage");
        if(usage==null||usage.isNull())return new OpenAiResponsesAgentModelClient.Counters(null,null,null,null);
        if(!usage.isObject())throw invalid("Invalid Responses usage");
        Long input=count(usage.get("input_tokens")),output=count(usage.get("output_tokens"));
        Long cached=count(usage.path("input_tokens_details").get("cached_tokens"));
        Long reasoning=count(usage.path("output_tokens_details").get("reasoning_tokens"));
        if(input!=null&&cached!=null&&cached>input||output!=null&&reasoning!=null&&reasoning>output)throw invalid("Invalid Responses usage subsets");
        return new OpenAiResponsesAgentModelClient.Counters(input,output,cached,reasoning);
    }
    private static Long count(JsonNode value){
        if(value==null||value.isMissingNode()||value.isNull())return null;
        if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0||value.asLong()>Integer.MAX_VALUE)throw invalid("Invalid Responses token count");
        return value.asLong();
    }
    public static Parsed parse(JsonNode response,List<ToolDefinition> tools,ObjectMapper mapper){
        if(response==null||!response.isObject())throw invalid("Responses body must be an object");
        var counters=counters(response);
        if(!"completed".equals(text(response.get("status"))))throw invalid("Responses request did not complete");
        if(response.hasNonNull("error"))throw invalid("Responses provider returned an error");
        String id=text(response.get("id"));if(id==null||id.isBlank()||id.length()>128)throw invalid("Responses id is required");
        var output=response.get("output");if(output==null||!output.isArray())throw invalid("Responses output is required");
        List<ModelMessage> visible=new ArrayList<>();String lastTextPhase=null;int calls=0;
        Map<String,ToolDefinition> definitions=new HashMap<>();for(var definition:tools)definitions.put(ProviderToolNames.encode(definition.name()),definition);
        for(JsonNode item:output){
            switch(Objects.requireNonNullElse(text(item.get("type")),"")){
                case "reasoning"->{
                    String encrypted=text(item.get("encrypted_content"));
                    if(encrypted==null||encrypted.isBlank())throw invalid("Responses reasoning lacks required encrypted continuation");
                }
                case "message"->{
                    if(!"assistant".equals(text(item.get("role"))))throw invalid("Responses message role is invalid");
                    if(item.hasNonNull("status")&&!"completed".equals(text(item.get("status"))))throw invalid("Responses message did not complete");
                    String phase=text(item.get("phase"));
                    if(phase!=null&&!Set.of("commentary","final_answer").contains(phase))throw invalid("Responses message phase is unsupported");
                    JsonNode content=item.get("content");if(content==null||!content.isArray())throw invalid("Responses message content is invalid");
                    StringBuilder text=new StringBuilder();
                    for(var part:content){if(!"output_text".equals(text(part.get("type"))))throw invalid("Responses message contains refusal or unsupported content");String value=text(part.get("text"));if(value==null)throw invalid("Responses text is invalid");text.append(value);}
                    if(!text.isEmpty()){visible.add(ModelMessage.assistant(text.toString()));lastTextPhase=phase;}
                }
                case "function_call"->{
                    if(item.hasNonNull("status")&&!"completed".equals(text(item.get("status"))))throw invalid("Responses tool call did not complete");
                    if(++calls>1)throw invalid("Multiple Responses tool calls are unsupported");
                    String callId=text(item.get("call_id")),name=text(item.get("name")),arguments=text(item.get("arguments"));
                    if(callId==null||callId.isBlank()||callId.length()>128||name==null||arguments==null)throw invalid("Responses tool call fields are missing");
                    ToolDefinition definition=definitions.get(name);if(definition==null)throw invalid("Responses tool is not enabled");
                    JsonNode values;
                    try{values=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);}catch(RuntimeException bad){throw invalid("Responses tool arguments are not complete JSON");}
                    if(values==null||!values.isObject())throw invalid("Responses tool arguments must be an object");
                    Map<String,Object> args=mapper.convertValue(values,Map.class);var normalized=ToolArguments.managed(args);
                    if(!definition.schema().validate(normalized).valid())throw invalid("Responses tool arguments do not satisfy schema");
                    visible.add(ModelMessage.assistantToolCall(new ToolCall(callId,definition.name(),normalized)));
                }
                default->throw invalid("Responses output item is unsupported");
            }
        }
        boolean hasText=visible.stream().anyMatch(m->!m.content().isBlank());
        if(!hasText&&calls==0)throw invalid("Responses contains no visible decision");
        var action=calls==1?AssistantTurnDecision.NextAction.TOOL_CALL:"commentary".equals(lastTextPhase)?AssistantTurnDecision.NextAction.CONTINUE:AssistantTurnDecision.NextAction.FINAL;
        int input=counters.inputTokens()==null?0:Math.toIntExact(counters.inputTokens());int out=counters.outputTokens()==null?0:Math.toIntExact(counters.outputTokens());
        int total;try{total=Math.addExact(input,out);}catch(ArithmeticException bad){throw invalid("Responses total usage exceeds supported range");}
        return new Parsed(id,List.copyOf(visible),action,new TokenUsage(input,out,total),
                counters.inputTokens()!=null&&counters.outputTokens()!=null?UsageSource.REPORTED:UsageSource.UNKNOWN,
                new AssistantTurnDecision.UsageDetails(counters.cachedInputTokens(),counters.reasoningTokens()),counters,output.deepCopy());
    }
    static OpenAiResponsesAgentModelClient.Counters failureCounters(ModelClientException failure, OpenAiResponsesAgentModelClient.Counters fallback) {
        if (failure instanceof com.agentflow.llm.http.BoundedResponsesTransport.ProviderFailure provider) {
            try { return counters(provider.usageResponse()); }
            catch (ModelClientException malformedUsage) { return fallback; }
        }
        return fallback;
    }
    static String text(JsonNode value){return value!=null&&value.isTextual()?value.asText():null;}
    static ModelClientException invalid(String message){return new ModelClientException(ModelClientException.INVALID_MODEL_DECISION,message);}
}
