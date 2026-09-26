package com.agentflow.demo.delivery;
import com.agentflow.llm.*;
import com.agentflow.core.model.ModelPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
class DeliveryModeTest {
    @Test void launcherRequiresExplicitOfflineAndConfigurationDoesNotActivateItself() {
        assertThatThrownBy(() -> DeliveryDemoLauncher.main(new String[0])).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--offline");
        new ApplicationContextRunner().withUserConfiguration(DeliveryDemoLauncher.Configuration.class).run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(com.agentflow.core.model.AgentModelClient.class));
    }
    @Test void missingRealModelKeyFailsBeforeNetworkWithoutFixtureFallback() {
        var properties = new AgentFlowProperties();
        properties.model().setProvider("openai"); properties.model().setBaseUrl("http://127.0.0.1:1");
        properties.model().setApiKey(""); properties.model().setChatModel("fixture-config-check");
        var client = new OpenAiAgentModelClient(new OpenAiCompatibleModelClient(properties, RestClient.builder()));
        assertThatThrownBy(() -> client.generate(new ModelPrompt("system", "hello"))).isInstanceOf(ModelClientException.class).hasMessageContaining("API key");
    }
    @Test void productionSourcesDoNotImportFixtureOrLoadDotEnv() throws Exception {
        try (var sources = Files.walk(Path.of("src/main"))) {
            for (var path : sources.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".yml")).toList()) {
                var text = Files.readString(path);
                assertThat(text).doesNotContain("DeliveryDemoLauncher", "delivery-fixture", "dotenv", "import: .env");
            }
        }
    }
}
