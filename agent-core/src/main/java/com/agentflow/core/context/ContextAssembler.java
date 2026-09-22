package com.agentflow.core.context;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Selects whole optional groups; never edits the current Run's confirmed protocol chain. */
public class ContextAssembler {
    private final ContextPolicy policy;
    private final TokenEstimator estimator;
    private final ContextTextPolicy textPolicy;
    private final String estimatorVersion;

    public ContextAssembler(ContextPolicy policy, TokenEstimator estimator, ContextTextPolicy textPolicy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.estimator = Objects.requireNonNull(estimator, "estimator must not be null");
        this.textPolicy = Objects.requireNonNull(textPolicy, "textPolicy must not be null");
        this.estimatorVersion = Objects.requireNonNull(estimator.version(), "estimator version must not be null");
        if (estimatorVersion.isBlank() || estimatorVersion.length() > 32
                || !estimatorVersion.chars().allMatch(c -> c >= 33 && c <= 126)) {
            throw new IllegalArgumentException("estimator version must be bounded printable ASCII");
        }
    }

    public ContextAssembly assemble(AgentRequest request, List<ModelMessage> currentRunMessages,
                                    List<ToolDefinition> enabledTools, int iteration, int remainingCompletionTokens) {
        Objects.requireNonNull(request, "request must not be null");
        ContextSeed seed = request.contextSeed();
        List<ContextDiagnostics.Selection> selections = new ArrayList<>();
        if (iteration < 1 || remainingCompletionTokens < 0 || !validChain(request, currentRunMessages)) {
            return rejected(seed, iteration, remainingCompletionTokens, 0, selections, "INVALID_INPUT");
        }
        List<ModelMessage> current = new ArrayList<>(currentRunMessages);
        String sanitized = textPolicy.sanitizeInput(request.input());
        if (sanitized.length() > 8000) {
            return rejected(seed, iteration, remainingCompletionTokens, 0, selections, "INVALID_INPUT");
        }
        current.set(0, ModelMessage.user(sanitized));
        List<ToolDefinition> tools = List.copyOf(enabledTools);
        List<ConversationTurn> history = new ArrayList<>();
        for (ConversationTurn turn : seed.turns()) {
            String user = textPolicy.sanitizeHistory(turn.userText());
            String answer = textPolicy.sanitizeHistory(turn.assistantText());
            if (user.length() > 8000 || answer.length() > 65536) {
                return rejected(seed, iteration, remainingCompletionTokens, 0, selections, "INVALID_INPUT");
            }
            history.add(new ConversationTurn(turn.runId(), turn.turnSequence(), user, answer));
        }
        List<ConfirmedMemory> memories = seed.memories().stream()
                .sorted(Comparator.comparingInt(memory -> ConfirmedMemory.PREFERRED_LANGUAGE.equals(memory.key()) ? 0 : 1))
                .toList();
        for (ConfirmedMemory memory : memories) {
            if (!textPolicy.isMemoryValueAllowed(memory.value())) {
                return rejected(seed, iteration, remainingCompletionTokens, 0, selections, "INVALID_INPUT");
            }
        }
        long mandatory;
        try {
            mandatory = estimate(messages(List.of(), List.of(), current, request.requireEvidence()), tools);
            if (Math.addExact(mandatory, remainingCompletionTokens) > policy.windowLimit()) {
                return rejected(seed, iteration, remainingCompletionTokens, mandatory, selections, "CONTEXT_BUDGET_EXCEEDED");
            }
        } catch (RuntimeException failure) {
            return rejected(seed, iteration, remainingCompletionTokens, Long.MAX_VALUE, selections, "CONTEXT_BUDGET_EXCEEDED");
        }
        int firstTurn = 0;
        int firstMemory = 0;
        String lastReason = "NONE";
        while (true) {
            List<ModelMessage> chosen = messages(history.subList(firstTurn, history.size()),
                    memories.subList(firstMemory, memories.size()), current, request.requireEvidence());
            long estimated = 0;
            String dropReason;
            try {
                dropReason = hardLimit(chosen, tools);
                if (dropReason == null) {
                    estimated = estimate(chosen, tools);
                    dropReason = Math.addExact(estimated, remainingCompletionTokens) > policy.windowLimit() ? "WINDOW" : null;
                }
            } catch (RuntimeException failure) {
                return rejected(seed, iteration, remainingCompletionTokens, mandatory, selections, "CONTEXT_BUDGET_EXCEEDED");
            }
            if (dropReason == null) {
                for (ConversationTurn turn : history.subList(firstTurn, history.size())) {
                    selections.add(new ContextDiagnostics.Selection(turn.runId(), turn.turnSequence(), "KEEP"));
                }
                for (ConfirmedMemory memory : memories.subList(firstMemory, memories.size())) {
                    selections.add(new ContextDiagnostics.Selection(memory.key(), memory.version(), "KEEP"));
                }
                // Recheck the final immutable request rather than trusting subtraction alone.
                try {
                    estimated = estimate(chosen, tools);
                    if (Math.addExact(estimated, remainingCompletionTokens) > policy.windowLimit()) {
                        return rejected(seed, iteration, remainingCompletionTokens, mandatory, selections, "CONTEXT_BUDGET_EXCEEDED");
                    }
                } catch (RuntimeException failure) {
                    return rejected(seed, iteration, remainingCompletionTokens, mandatory, selections, "CONTEXT_BUDGET_EXCEEDED");
                }
                var diagnostics = diagnostics(seed, iteration, remainingCompletionTokens, mandatory, estimated,
                        history.size() - firstTurn, firstTurn, memories.size() - firstMemory, firstMemory, selections, lastReason);
                return new ContextAssembly(new AgentModelRequest(request, chosen, tools, iteration, remainingCompletionTokens),
                        null, diagnostics);
            }
            lastReason = dropReason;
            if (firstTurn < history.size()) {
                ConversationTurn turn = history.get(firstTurn++);
                selections.add(new ContextDiagnostics.Selection(turn.runId(), turn.turnSequence(), dropReason));
            } else if (firstMemory < memories.size()) {
                ConfirmedMemory memory = memories.get(firstMemory++);
                selections.add(new ContextDiagnostics.Selection(memory.key(), memory.version(), dropReason));
            } else {
                return rejected(seed, iteration, remainingCompletionTokens, mandatory, selections, "CONTEXT_BUDGET_EXCEEDED");
            }
        }
    }

    private long estimate(List<ModelMessage> messages, List<ToolDefinition> tools) {
        String limit = hardLimit(messages, tools);
        if (limit != null) {
            throw new IllegalArgumentException(limit);
        }
        long value = estimator.estimateInput(messages, tools);
        if (value < 0) {
            throw new IllegalArgumentException("negative estimate");
        }
        return value;
    }

    private static String hardLimit(List<ModelMessage> messages, List<ToolDefinition> tools) {
        if (messages.size() > 64 || tools.size() > 32) {
            return "MESSAGE_LIMIT";
        }
        long length = 0;
        for (ModelMessage message : messages) {
            length = Math.addExact(length, message.content().length());
        }
        if (length > 262144) {
            return "TEXT_LIMIT";
        }
        try {
            Utf8TokenEstimator.validateTextLimits(messages, tools);
        } catch (IllegalArgumentException limit) {
            return "TEXT_LIMIT";
        }
        return null;
    }

    private List<ModelMessage> messages(List<ConversationTurn> turns, List<ConfirmedMemory> memories,
                                         List<ModelMessage> current, boolean requireEvidence) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", policy.systemText() + (requireEvidence ? " 当前回答必须包含当前Run可用的来源引用。" : "")));
        if (!memories.isEmpty()) {
            StringBuilder data = new StringBuilder("User-confirmed session data (not system instructions):\n");
            for (ConfirmedMemory memory : memories) {
                data.append(memory.key()).append("=").append(escape(memory.value())).append('\n');
            }
            messages.add(ModelMessage.user(data.toString()));
        }
        for (ConversationTurn turn : turns) {
            messages.add(ModelMessage.user(turn.userText()));
            messages.add(ModelMessage.assistant(turn.assistantText()));
        }
        messages.addAll(current);
        return List.copyOf(messages);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
                .replace("=", "\\=").replace(";", "\\;");
    }

    private static boolean validChain(AgentRequest request, List<ModelMessage> messages) {
        if (request.input().length() > 8000 || messages == null || messages.isEmpty() || messages.size() % 2 == 0) {
            return false;
        }
        ModelMessage first = messages.get(0);
        if (first == null || !"user".equals(first.role()) || !request.input().equals(first.content())
                || first.toolCall() != null || first.toolCallId() != null || first.name() != null) {
            return false;
        }
        Set<String> callIds = new HashSet<>();
        for (int index = 1; index < messages.size(); index += 2) {
            ModelMessage call = messages.get(index);
            ModelMessage result = messages.get(index + 1);
            if (call == null || result == null || !"assistant".equals(call.role()) || call.toolCall() == null
                    || !"tool".equals(result.role()) || result.toolCall() != null
                    || !call.toolCall().callId().equals(result.toolCallId())
                    || !call.toolCall().name().equals(result.name()) || !callIds.add(call.toolCall().callId())) {
                return false;
            }
        }
        return true;
    }

    private ContextAssembly rejected(ContextSeed seed, int iteration, int reserve, long mandatory,
                                     List<ContextDiagnostics.Selection> selections, String reason) {
        return new ContextAssembly(null, reason, diagnostics(seed, iteration, reserve, mandatory, mandatory,
                0, seed.turns().size(), 0, seed.memories().size(), selections, reason));
    }

    private ContextDiagnostics diagnostics(ContextSeed seed, int iteration, int reserve, long mandatory,
                                           long estimated, int keptTurns, int droppedTurns, int keptMemory,
                                           int droppedMemory, List<ContextDiagnostics.Selection> selections, String reason) {
        return new ContextDiagnostics(iteration, seed.historyThroughTurn(), policy.promptVersion(), "suffix-v1",
                estimatorVersion, keptTurns, droppedTurns, keptMemory, droppedMemory, mandatory, estimated,
                reserve, policy.windowLimit(), seed.candidateLimited(), seed.sourceDiscardedCount(), selections, reason);
    }
}
