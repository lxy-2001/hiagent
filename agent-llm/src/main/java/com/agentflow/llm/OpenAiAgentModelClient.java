package com.agentflow.llm;

import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.ModelDecision;
import com.agentflow.core.model.ModelPrompt;

import java.util.Objects;

/** Agent decision adapter backed by the shared OpenAI-compatible transport. */
public final class OpenAiAgentModelClient implements AgentModelClient {
    private final OpenAiCompatibleModelClient transport;

    public OpenAiAgentModelClient(OpenAiCompatibleModelClient transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    @Override
    public ModelDecision decide(AgentModelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return ProviderDecisionMapper.decision(transport.completeAgent(request), transport.objectMapper());
        } catch (ModelClientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Jackson/provider conversion failures must not escape as an unstable 500.
            throw new ModelClientException(ModelClientException.MALFORMED_MODEL_RESPONSE,
                    "Invalid model response", ex);
        }
    }

    /** Legacy text helper retained for callers of the pre-Feature-002 adapter; not a Core port. */
    public String generate(ModelPrompt prompt) {
        return transport.generate(prompt);
    }
}
