package com.agentflow.core.tool;

import java.util.Map;

/** Local policy lookup. Both model visibility and dispatch use the internal name. */
@FunctionalInterface
public interface ToolExecutionPolicy {
    ToolPolicyDecision decide(String toolName);
    static ToolExecutionPolicy denyAll() { return name -> ToolPolicyDecision.denied(); }
    static ToolExecutionPolicy rules(Map<String,ToolPolicyDecision> rules) {
        Map<String,ToolPolicyDecision> snapshot = Map.copyOf(rules);
        return name -> snapshot.getOrDefault(name, ToolPolicyDecision.denied());
    }
}
