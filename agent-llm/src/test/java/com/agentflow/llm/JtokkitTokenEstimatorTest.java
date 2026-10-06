package com.agentflow.llm;
import com.agentflow.core.context.TokenEstimator;
import com.agentflow.core.model.ModelMessage;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class JtokkitTokenEstimatorTest {
 @Test void estimatesSerializedResponsesAliasesAndEscapedLongArguments() {
  var mapper = new tools.jackson.databind.ObjectMapper();
  var encoding = com.knuddels.jtokkit.Encodings.newDefaultEncodingRegistry()
      .getEncoding("o200k_base").orElseThrow();
  var arguments = com.agentflow.core.tool.ToolArguments.managed(
      Map.of("text", "\n\"\\\u0001".repeat(1500)));
  var call = new com.agentflow.core.tool.ToolCall("call-1", "files.read-content", arguments);
  var schema = new com.agentflow.core.tool.ToolSchema(
      Map.of("text", com.agentflow.core.tool.ParameterSpec.requiredString(20000)),
      Set.of("text"), false);
  var tool = new com.agentflow.core.tool.ToolDefinition("files.read-content",
      "Read a file", com.agentflow.core.tool.RiskLevel.LOW, schema);
  var messages = List.of(ModelMessage.user("inspect"), ModelMessage.assistantToolCall(call),
      new ModelMessage("tool", "result\nquoted \"data\"", "files.read-content", "call-1", null));
  long expected = 8;
  for (var message : messages) {
   expected += 8 + encoding.countTokens(mapper.writeValueAsString(
       OpenAiResponsesAgentModelClient.message(message, mapper)));
  }
  expected += 24 + encoding.countTokens(mapper.writeValueAsString(
      OpenAiResponsesAgentModelClient.tool(tool)));
  assertEquals(expected, new JtokkitTokenEstimator("o200k_base").estimateInput(messages,List.of(tool)));
 }
 @Test void countsUnabridgedMessagesAndRejectsUnknownEncoding() {
  TokenEstimator estimator=new JtokkitTokenEstimator("o200k_base");
  var messages=new ArrayList<ModelMessage>();
  for(int i=0;i<80;i++) messages.add(ModelMessage.user("hello world ".repeat(400)));
  assertTrue(estimator.estimateInput(messages,List.of())>32000);
  assertTrue(estimator.estimateInput(messages,List.of())>estimator.estimateInput(messages.subList(0,64),List.of()));
  var call=new com.agentflow.core.tool.ToolCall("c","t",new com.agentflow.core.tool.ToolArguments(Map.of("x","long args ".repeat(400), "y","more args ".repeat(400))));
  var tool=new com.agentflow.core.tool.ToolDefinition("t","large schema ".repeat(100),com.agentflow.core.tool.RiskLevel.LOW,new com.agentflow.core.tool.ToolSchema(Map.of()));
  var withTool = estimator.estimateInput(List.of(ModelMessage.assistantToolCall(call)),List.of(tool));
  var withoutTool = estimator.estimateInput(List.of(ModelMessage.assistantToolCall(call)),List.of());
  assertTrue(withTool > withoutTool);
  assertTrue(withTool > 1000);
  assertThrows(IllegalArgumentException.class,()->new JtokkitTokenEstimator("unknown"));
 }
}