package com.agentflow.core.context;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.*;
import java.util.List;
/** Bound to one trusted owner/session/run, supplied by the application. */
public interface ContextSession extends AutoCloseable {
 HistoryStore.Scope scope();
 List<ModelMessage> prepare(List<ToolDefinition> tools,int remainingOutputTokens,int iteration);
 /** Compatible extension for preparations that issue their own model requests. */
 default List<ModelMessage> prepare(List<ToolDefinition> tools,int remainingOutputTokens,long remainingInputTokens,int iteration) {
  return prepare(tools,remainingOutputTokens,iteration);
 }
 void confirm(ModelDecision decision);
 void intent(ToolCall call);
 ToolResult result(ToolResult result);
 default void modelFailure(String code) { }
 /** Drains summary budget charges once, including failed preparations. Unknown usage is conservatively charged; billing diagnostics retain unknown. */
 default com.agentflow.core.chat.TokenUsage drainPreparationUsage() { return com.agentflow.core.chat.TokenUsage.empty(); }
 @Override default void close() { }
}
