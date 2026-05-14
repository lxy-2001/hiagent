package com.agentflow.web.chat;

import com.agentflow.core.chat.ChatCompletionRequest;
import com.agentflow.core.chat.ChatCompletionResponse;
import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.web.memory.InMemoryShortTermMemory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceTest {

    @Test
    void reusesSessionHistoryAndAppendsCompletedTurns() {
        CapturingChatModelClient client = new CapturingChatModelClient();
        InMemoryShortTermMemory memory = new InMemoryShortTermMemory();
        ChatService service = new ChatService(client, memory);

        ChatResponse first = service.chat(new ChatRequest(null, "第一轮", null, null, null, null));
        ChatResponse second = service.chat(new ChatRequest(first.sessionId(), "第二轮", null, null, null, null));

        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(client.requests).hasSize(2);
        assertThat(client.requests.get(1).messages())
                .extracting(message -> message.role() + ":" + message.content())
                .containsExactly("user:第一轮", "assistant:answer-1", "user:第二轮");
        assertThat(memory.recentMessages(first.sessionId(), 20))
                .containsExactly("USER: 第一轮", "ASSISTANT: answer-1", "USER: 第二轮", "ASSISTANT: answer-2");
    }

    private static class CapturingChatModelClient implements ChatModelClient {

        private final List<ChatCompletionRequest> requests = new ArrayList<>();

        @Override
        public ChatCompletionResponse complete(ChatCompletionRequest request) {
            requests.add(request);
            return new ChatCompletionResponse("deepseek", "deepseek-v4-pro",
                    "answer-" + requests.size(), TokenUsage.empty(), false);
        }

        @Override
        public ChatCompletionResponse stream(ChatCompletionRequest request, Consumer<String> deltaConsumer) {
            return complete(request);
        }
    }
}
