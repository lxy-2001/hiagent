package com.agentflow.spring.autoconfigure;

import com.agentflow.core.chat.ChatCompletionRequest;
import com.agentflow.core.chat.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpMethod.POST;

class OpenAiCompatibleModelClientTest {

    @Test
    void sendsDeepSeekChatCompletionRequestAndParsesResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(properties(), builder);

        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "model": "deepseek-v4-pro",
                          "messages": [
                            {"role": "user", "content": "你好"}
                          ],
                          "temperature": 0.7,
                          "max_tokens": 2048
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "id": "chatcmpl-test",
                          "model": "deepseek-v4-pro",
                          "choices": [
                            {"message": {"role": "assistant", "content": "你好，我是 AgentFlow。"}}
                          ],
                          "usage": {
                            "prompt_tokens": 3,
                            "completion_tokens": 5,
                            "total_tokens": 8
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = client.complete(new ChatCompletionRequest(List.of(ChatMessage.user("你好")),
                null, 0.7, 2048));

        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.model()).isEqualTo("deepseek-v4-pro");
        assertThat(response.content()).isEqualTo("你好，我是 AgentFlow。");
        assertThat(response.usage().promptTokens()).isEqualTo(3);
        assertThat(response.usage().completionTokens()).isEqualTo(5);
        assertThat(response.usage().totalTokens()).isEqualTo(8);
        assertThat(response.mocked()).isFalse();
        server.verify();
    }

    @Test
    void parsesStreamingResponseAndIgnoresReasoningContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(properties(), builder);

        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "model": "deepseek-v4-pro",
                          "messages": [
                            {"role": "user", "content": "介绍一下你自己"}
                          ],
                          "stream": true
                        }
                        """))
                .andRespond(withSuccess("""
                        data: {"model":"deepseek-v4-pro","choices":[{"delta":{"reasoning_content":"hidden"}}]}

                        data: {"model":"deepseek-v4-pro","choices":[{"delta":{"content":"你"}}]}

                        data: {"model":"deepseek-v4-pro","choices":[{"delta":{"content":"好"}}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

                        data: [DONE]

                        """, MediaType.TEXT_EVENT_STREAM));

        List<String> deltas = new ArrayList<>();
        var response = client.stream(new ChatCompletionRequest(List.of(ChatMessage.user("介绍一下你自己")),
                null, null, null), deltas::add);

        assertThat(deltas).containsExactly("你", "好");
        assertThat(response.content()).isEqualTo("你好");
        assertThat(response.usage().totalTokens()).isEqualTo(6);
        assertThat(response.mocked()).isFalse();
        server.verify();
    }

    @Test
    void fallsBackToDeterministicEmbeddingWhenProviderDoesNotSupportEmbeddings() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentFlowProperties properties = properties();
        properties.model().setEmbeddingDimensions(4);
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(properties, builder);

        server.expect(requestTo("https://api.deepseek.com/embeddings"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        List<Double> vector = client.embed("hello");

        assertThat(vector).hasSize(4);
        server.verify();
    }

    private AgentFlowProperties properties() {
        AgentFlowProperties properties = new AgentFlowProperties();
        properties.model().setProvider("deepseek");
        properties.model().setBaseUrl("");
        properties.model().setApiKey("test-api-key");
        properties.model().setChatModel("deepseek-v4-pro");
        return properties;
    }
}
