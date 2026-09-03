package com.agentflow.llm;

import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.ModelMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class OpenAiAgentModelClientErrorTest {
    @Test
    void rejectsMalformedArgumentsMultipleCallsMissingIdUnknownFinishAndNegativeUsage() {
        String[] payloads = {
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"x\",\"arguments\":\"not-json\"}}]}}]}",
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"x\",\"arguments\":\"{}\"}},{\"id\":\"c2\",\"function\":{\"name\":\"x\",\"arguments\":\"{}\"}}]}}]}",
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"x\",\"arguments\":\"{}\"}}]}}]}",
                "{\"choices\":[{\"finish_reason\":\"weird\",\"message\":{\"content\":\"answer\"}}]}",
                "{\"choices\":[{\"message\":{\"tool_calls\":\"bad\",\"content\":\"answer\"}}]}",
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c\",\"type\":\"custom\",\"function\":{\"name\":\"x\",\"arguments\":\"{}\"}}]}}]}",
                "{\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":\"bad\"}",
                "{\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":-1}}"
        };
        for (String payload : payloads) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                    .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
            OpenAiAgentModelClient client = new OpenAiAgentModelClient(
                    new OpenAiCompatibleModelClient(properties(), builder));
            AgentModelRequest request = new AgentModelRequest("task", "session", "user", "input",
                    List.of(ModelMessage.user("input")), List.of(), 1);
            assertThatThrownBy(() -> client.decide(request)).isInstanceOf(ModelClientException.class);
            server.verify();
        }
    }

    @Test
    void redactsQuotedJsonCredentialFields() {
        String sanitized = ModelClientException.sanitize("{\"api_key\":\"secret-value\",\"password\":\"pw\"}");

        org.assertj.core.api.Assertions.assertThat(sanitized)
                .doesNotContain("secret-value")
                .doesNotContain("pw")
                .contains("redacted");
    }

    @Test
    void doesNotExposeCredentialThroughExceptionCause() {
        ModelClientException exception = new ModelClientException(
                ModelClientException.PROVIDER_ERROR,
                "apiKey=secret-value",
                new IllegalArgumentException("password=another-secret"));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .doesNotContain("secret-value");
        org.assertj.core.api.Assertions.assertThat(exception.getCause().getMessage())
                .doesNotContain("another-secret");
    }

    @Test
    void missingApiKeyFailsBeforeTransport() {
        RestClient.Builder builder = RestClient.builder();
        AgentFlowProperties properties = properties();
        properties.model().setApiKey("");
        OpenAiAgentModelClient client = new OpenAiAgentModelClient(
                new OpenAiCompatibleModelClient(properties, builder));
        AgentModelRequest request = new AgentModelRequest("task", "session", "user", "input",
                List.of(ModelMessage.user("input")), List.of(), 1);

        assertThatThrownBy(() -> client.decide(request)).isInstanceOf(ModelClientException.class)
                .hasMessageContaining("API key");
    }


    @Test
    void derivesMissingTotalUsageAndExposesStableDecisionCode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess(
                        "{\"id\":\"d\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}",
                        MediaType.APPLICATION_JSON));
        OpenAiAgentModelClient client = new OpenAiAgentModelClient(
                new OpenAiCompatibleModelClient(properties(), builder));
        AgentModelRequest request = new AgentModelRequest("task", "session", "user", "input",
                List.of(ModelMessage.user("input")), List.of(), 1);

        var decision = client.decide(request);

        org.assertj.core.api.Assertions.assertThat(decision.usage())
                .isEqualTo(new com.agentflow.core.chat.TokenUsage(3, 2, 5));
        server.verify();
    }

    @Test
    void sanitizesProviderResponseAndApiKeyFromFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Bearer test-api-key?token=secret-value\"}"));
        OpenAiAgentModelClient client = new OpenAiAgentModelClient(
                new OpenAiCompatibleModelClient(properties(), builder));
        AgentModelRequest request = new AgentModelRequest("task", "session", "user", "input",
                List.of(ModelMessage.user("input")), List.of(), 1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.decide(request))
                .isInstanceOf(ModelClientException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .doesNotContain("test-api-key")
                .doesNotContain("secret-value");
        server.verify();
    }

    private AgentFlowProperties properties() {
        AgentFlowProperties properties = new AgentFlowProperties();
        properties.model().setProvider("deepseek");
        properties.model().setApiKey("test-api-key");
        properties.model().setChatModel("deepseek-v4-pro");
        return properties;
    }
}
