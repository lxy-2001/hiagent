package com.agentflow.llm;

import com.agentflow.core.model.ModelPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;
import org.springframework.http.MediaType;

class OpenAiAgentModelClientTest {

    @Test
    void generatesAnAgentDecisionThroughTheSharedTransport() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiAgentModelClient client = new OpenAiAgentModelClient(
                new OpenAiCompatibleModelClient(properties(), builder));

        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "model": "deepseek-v4-pro",
                          "messages": [
                            {"role": "system", "content": "system"},
                            {"role": "user", "content": "plan"}
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {"model":"deepseek-v4-pro","choices":[{"message":{"content":"decision"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.generate(new ModelPrompt("system", "plan"))).isEqualTo("decision");
        server.verify();
    }

    @Test
    void failsBeforeNetworkWhenAgentApiKeyIsMissing() {
        RestClient.Builder builder = RestClient.builder();
        AgentFlowProperties properties = properties();
        properties.model().setApiKey("");
        OpenAiAgentModelClient client = new OpenAiAgentModelClient(
                new OpenAiCompatibleModelClient(properties, builder));

        assertThatThrownBy(() -> client.generate(new ModelPrompt("system", "plan")))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("API key");
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
