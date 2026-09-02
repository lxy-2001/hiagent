package com.agentflow.llm;

import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.ModelPrompt;

import java.util.Objects;

/**
 * Agent decision-model adapter backed by the shared OpenAI-compatible transport.
 */
public final class OpenAiAgentModelClient implements AgentModelClient {

    private final OpenAiCompatibleModelClient transport;

    public OpenAiAgentModelClient(OpenAiCompatibleModelClient transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    @Override
    public String generate(ModelPrompt prompt) {
        return transport.generate(prompt);
    }
}
