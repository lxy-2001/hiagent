package com.agentflow.demo.evaluation;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.context.*;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolDefinition;
import java.util.ArrayList;
import java.util.List;

final class ObservingContextAssembler extends ContextAssembler {
    private final List<ContextDiagnostics> observations = new ArrayList<>();
    private final java.util.Map<String, List<ContextDiagnostics>> runs = new java.util.HashMap<>();
    ObservingContextAssembler(ContextPolicy policy) { super(policy, new Utf8TokenEstimator(), new ContextTextPolicy()); }
    @Override public ContextAssembly assemble(AgentRequest request, List<ModelMessage> messages,
                                               List<ToolDefinition> tools, int iteration, int remaining) {
        var assembly = super.assemble(request, messages, tools, iteration, remaining);
        observations.add(assembly.diagnostics());
        runs.computeIfAbsent(request.taskId(), ignored -> new ArrayList<>()).add(assembly.diagnostics());
        return assembly;
    }
    List<ContextDiagnostics> diagnostics(String runId) { return List.copyOf(runs.getOrDefault(runId, List.of())); }
    List<ContextDiagnostics> diagnostics() { return List.copyOf(observations); }
}
