package com.agentflow.core.model;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.ToolCall;
import java.util.*;
/** Visible ordered response items; opaque provider state stays in the adapter. */
public record AssistantTurnDecision(String decisionId, List<ModelMessage> orderedItems, NextAction nextAction,
 TokenUsage usage, UsageSource usageSource, UsageDetails usageDetails, String continuationRef) implements ModelDecision {
 public enum NextAction { FINAL, CONTINUE, TOOL_CALL }
 public record UsageDetails(Long cachedInputTokens, Long reasoningTokens) {
  public UsageDetails { if(cachedInputTokens!=null && cachedInputTokens<0 || reasoningTokens!=null && reasoningTokens<0) throw new IllegalArgumentException("negative usage"); }
 }
 public AssistantTurnDecision {
  if(decisionId==null || decisionId.isBlank()) throw new IllegalArgumentException("decisionId required");
  orderedItems=List.copyOf(orderedItems); Objects.requireNonNull(nextAction); Objects.requireNonNull(usage); Objects.requireNonNull(usageSource);
  if(orderedItems.stream().anyMatch(m->!m.role().equals("assistant"))) throw new IllegalArgumentException("assistant items only");
  long calls=orderedItems.stream().filter(m->m.toolCall()!=null).count();
  if(calls>1 || (nextAction==NextAction.TOOL_CALL)!=(calls==1)) throw new IllegalArgumentException("exactly one call required for TOOL_CALL");
  if(nextAction==NextAction.FINAL && orderedItems.stream().map(ModelMessage::content).allMatch(String::isBlank)) throw new IllegalArgumentException("final answer required");
 }
 public ToolCall toolCall(){ return orderedItems.stream().map(ModelMessage::toolCall).filter(Objects::nonNull).findFirst().orElse(null); }
 public String visibleText(){ return orderedItems.stream().filter(m->m.toolCall()==null).map(ModelMessage::content).collect(java.util.stream.Collectors.joining("\n")); }
}
