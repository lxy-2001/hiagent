package com.agentflow.demo.workbench;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

class UiContextTest extends WorkbenchHttpTestSupport {
    @Test void returnsOnlySafeConfigurationFactsWithoutProbingProviders() throws Exception {
        var response = get("/api/demo/ui-context", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body = new ObjectMapper().readTree(response.body());
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.path("mode").asString()).isEqualTo("REAL_CONFIGURED");
        assertThat(body.path("modelLabel").asString()).isEqualTo("workbench-model");
        assertThat(body.path("ragEnabled").asBoolean()).isFalse();
        assertThat(body.path("mcpEnabled").asBoolean()).isFalse();
        assertThat(response.body()).doesNotContain("apiKey", "baseUrl", "secret", "password");
        verifyNoInteractions(model);
    }

    @Test void labelsAreBoundedAndSuspiciousValuesAreNotExposed() {
        assertThat(UiContextConfiguration.safeLabel(null)).isNull();
        assertThat(UiContextConfiguration.safeLabel("sk-fixture-secret")).isNull();
        assertThat(UiContextConfiguration.safeLabel("model?api_key=private")).isNull();
        assertThat(UiContextConfiguration.safeLabel("x".repeat(200))).hasSize(128);
    }

    @Test void fixtureBeanExplicitlyOverridesProductionDisplayFacts() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(UiContextConfiguration.UiContext.class, () ->
                        new UiContextConfiguration.UiContext(UiContextConfiguration.Mode.OFFLINE_FIXTURE, "fixture", true, true))
                .withUserConfiguration(UiContextConfiguration.class)
                .run(context -> assertThat(context.getBean(UiContextConfiguration.UiContext.class).mode())
                        .isEqualTo(UiContextConfiguration.Mode.OFFLINE_FIXTURE));
    }

    @Test void anonymousCallerCannotReadConfigurationFacts() throws Exception {
        assertThat(get("/api/demo/ui-context", false).statusCode()).isEqualTo(401);
    }
}
