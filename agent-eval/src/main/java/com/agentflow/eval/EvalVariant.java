package com.agentflow.eval;

/** The label is descriptive; effective fields, not this label, determine comparability. */
public record EvalVariant(String id, int contextWindow) {
    public EvalVariant {
        if (id == null || !id.matches("[a-z0-9-]{1,128}") || contextWindow < 1 || contextWindow > 131072) {
            throw new IllegalArgumentException("Invalid evaluation variant");
        }
    }
    public static EvalVariant baseline() { return new EvalVariant("baseline", 16384); }
    public static EvalVariant compactContext() { return new EvalVariant("compact-context", 4096); }
}
