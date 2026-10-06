package com.agentflow.core.context;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.*;
import java.util.List;
/** Bound to one trusted owner/session/run, supplied by the application. */
public interface ContextSession extends AutoCloseable {
 HistoryStore.Scope scope();
 List<ModelMessage> prepare(List<ToolDefinition> tools,int remainingOutputTokens,int iteration);
 void confirm(ModelDecision decision);
 void intent(ToolCall call);
 ToolResult result(ToolResult result);
 default void modelFailure(String code) { }
 @Override default void close() { }
}
