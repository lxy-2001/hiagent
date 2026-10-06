package com.agentflow.llm;
import com.agentflow.core.context.TokenEstimator;
import com.agentflow.core.model.ModelMessage;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class JtokkitTokenEstimatorTest {
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