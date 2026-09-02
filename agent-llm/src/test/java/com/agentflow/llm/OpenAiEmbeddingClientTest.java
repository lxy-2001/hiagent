package com.agentflow.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class OpenAiEmbeddingClientTest {

    @Test
    void reportsProviderFailureInsteadOfCreatingAHashVector() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentFlowProperties properties = properties();
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                new OpenAiCompatibleModelClient(properties, builder));

        server.expect(requestTo("https://api.deepseek.com/embeddings"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("Embedding");
        server.verify();
    }

    @Test
    void rejectsInvalidEmbeddingPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentFlowProperties properties = properties();
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                new OpenAiCompatibleModelClient(properties, builder));

        server.expect(requestTo("https://api.deepseek.com/embeddings"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":\"not-a-vector\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("Embedding");
        server.verify();
    }

    @Test
    void failsBeforeNetworkWhenEmbeddingApiKeyIsMissing() {
        RestClient.Builder builder = RestClient.builder();
        AgentFlowProperties properties = properties();
        properties.model().setApiKey("");
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                new OpenAiCompatibleModelClient(properties, builder));

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("API key");
    }

    private AgentFlowProperties properties() {
        AgentFlowProperties properties = new AgentFlowProperties();
        properties.model().setProvider("deepseek");
        properties.model().setBaseUrl("");
        properties.model().setApiKey("test-api-key");
        properties.model().setEmbeddingDimensions(4);
        return properties;
    }
}
