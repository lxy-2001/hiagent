package com.agentflow.web.chat;

import com.agentflow.core.chat.ChatCompletionRequest;
import com.agentflow.core.chat.ChatCompletionResponse;
import com.agentflow.core.chat.ChatMessage;
import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.memory.ShortTermMemory;
import com.agentflow.web.support.Ids;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class ChatService {

    private static final int HISTORY_LIMIT = 20;

    private final ChatModelClient chatModelClient;
    private final ShortTermMemory shortTermMemory;

    public ChatService(ChatModelClient chatModelClient, ShortTermMemory shortTermMemory) {
        this.chatModelClient = chatModelClient;
        this.shortTermMemory = shortTermMemory;
    }

    public ChatResponse chat(ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        ChatCompletionResponse completion = chatModelClient.complete(completionRequest(sessionId, request));
        saveTurn(sessionId, request.message(), completion.content());
        return toResponse(sessionId, completion);
    }

    public ChatResponse stream(ChatRequest request, Consumer<String> deltaConsumer) {
        String sessionId = resolveSessionId(request.sessionId());
        ChatCompletionResponse completion = chatModelClient.stream(completionRequest(sessionId, request), deltaConsumer);
        saveTurn(sessionId, request.message(), completion.content());
        return toResponse(sessionId, completion);
    }

    private ChatCompletionRequest completionRequest(String sessionId, ChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (hasText(request.systemPrompt())) {
            messages.add(ChatMessage.system(request.systemPrompt().strip()));
        }
        messages.addAll(history(sessionId));
        messages.add(ChatMessage.user(request.message().strip()));
        return new ChatCompletionRequest(messages, request.model(), request.temperature(), request.maxTokens());
    }

    private List<ChatMessage> history(String sessionId) {
        return shortTermMemory.recentMessages(sessionId, HISTORY_LIMIT).stream()
                .map(this::toChatMessage)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<ChatMessage> toChatMessage(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        if (raw.startsWith("USER: ")) {
            return Optional.of(ChatMessage.user(raw.substring("USER: ".length())));
        }
        if (raw.startsWith("ASSISTANT: ")) {
            return Optional.of(ChatMessage.assistant(raw.substring("ASSISTANT: ".length())));
        }
        return Optional.empty();
    }

    private void saveTurn(String sessionId, String userMessage, String assistantMessage) {
        shortTermMemory.appendUserMessage(sessionId, userMessage.strip());
        shortTermMemory.appendAssistantMessage(sessionId, assistantMessage == null ? "" : assistantMessage);
    }

    private ChatResponse toResponse(String sessionId, ChatCompletionResponse completion) {
        return new ChatResponse(sessionId, completion.provider(), completion.model(), completion.content(),
                completion.usage(), completion.mocked());
    }

    private String resolveSessionId(String sessionId) {
        return hasText(sessionId) ? sessionId.strip() : Ids.newId();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
