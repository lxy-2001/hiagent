package com.agentflow.core.chat;

import java.util.function.Consumer;

public interface ChatModelClient {

    ChatCompletionResponse complete(ChatCompletionRequest request);

    ChatCompletionResponse stream(ChatCompletionRequest request, Consumer<String> deltaConsumer);
}
