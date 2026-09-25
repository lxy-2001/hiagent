package com.agentflow.core.tool;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable application-owned authorization, independent of remote annotations. */
public record ToolPolicyDecision(Action action, RiskLevel risk, Effect effect,
                                 String actionSummary, Set<String> visibleArgumentNames) {
    public enum Action { ALLOW, REQUIRE_APPROVAL, DENY }
    public enum Effect { READ_ONLY, WRITE }

    public ToolPolicyDecision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(actionSummary, "actionSummary");
        if (action == Action.ALLOW && (risk == RiskLevel.HIGH || effect == Effect.WRITE))
            throw new IllegalArgumentException("HIGH or WRITE requires approval or denial");
        if (actionSummary.isBlank() || actionSummary.length() > 256)
            throw new IllegalArgumentException("action summary must contain 1..256 characters");
        visibleArgumentNames = Set.copyOf(visibleArgumentNames);
        if (visibleArgumentNames.size() > 32 || visibleArgumentNames.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("invalid visible argument names");
    }

    public String policyVersion() {
        return ToolArgumentDigest.fingerprint("hiagent-tool-policy-v1", Map.of(
                "action", action.name(), "risk", risk.name(), "effect", effect.name(),
                "summary", actionSummary, "visibleArguments", visibleArgumentNames.stream().sorted().toList()), 32 * 1024);
    }

    public static ToolPolicyDecision denied() {
        return new ToolPolicyDecision(Action.DENY, RiskLevel.HIGH, Effect.WRITE, "Tool denied", Set.of());
    }
}
