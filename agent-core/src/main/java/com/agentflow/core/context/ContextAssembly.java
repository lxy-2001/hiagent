package com.agentflow.core.context;

import com.agentflow.core.model.AgentModelRequest;

/** Rejected assemblies never expose a sendable model request. */
public record ContextAssembly(AgentModelRequest request, String rejectionReason,
                              ContextDiagnostics diagnostics) {
    public boolean ready() {
        return rejectionReason == null;
    }
}

