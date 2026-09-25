package com.agentflow.tool;

import com.agentflow.core.tool.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Set;

@ConfigurationProperties("agentflow.tool-policy")
public record ToolPolicyProperties(List<Rule> rules) {
    public ToolPolicyProperties { rules = rules == null ? List.of() : List.copyOf(rules); }
    public record Rule(String toolName,ToolPolicyDecision.Action action,RiskLevel risk,
            ToolPolicyDecision.Effect effect,String actionSummary,Set<String> visibleArguments) { }
}
