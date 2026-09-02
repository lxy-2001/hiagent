package com.agentflow.llm;

import com.agentflow.core.chat.ChatCompletionRequest;
import com.agentflow.core.chat.ChatCompletionResponse;
import com.agentflow.core.chat.ChatModelClient;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Chat completion adapter backed by the shared OpenAI-compatible transport.
 */
public final class OpenAiChatModelClient implements ChatModelClient {

    private final OpenAiCompatibleModelClient transport;

    public OpenAiChatModelClient(OpenAiCompatibleModelClient transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    @Override
    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        return transport.complete(request);
    }

    @Override
    public ChatCompletionResponse stream(ChatCompletionRequest request, Consumer<String> deltaConsumer) {
        return transport.stream(request, deltaConsumer);
    }
}
