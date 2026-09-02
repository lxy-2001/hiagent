package com.agentflow.llm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentFlowPropertiesTest {

    @Test
    void jwtSecretHasNoProductionDefault() {
        assertThat(new AgentFlowProperties().security().jwt().getSecret()).isBlank();
    }

    @Test
    void bindsExplicitJwtSecretThroughBootConfigurationProperties() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32")
                .run(context -> assertThat(context.getBean(AgentFlowProperties.class).security().jwt().getSecret())
                        .isEqualTo("test-only-jwt-secret-which-is-long-enough-32"));
    }

    @Test
    void explicitJwtSecretRemainsAvailableForApplicationConfiguration() {
        AgentFlowProperties properties = new AgentFlowProperties();
        properties.security().jwt().setSecret("test-only-jwt-secret-which-is-long-enough-32");

        assertThat(properties.security().jwt().getSecret())
                .isEqualTo("test-only-jwt-secret-which-is-long-enough-32");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentFlowProperties.class)
    static class PropertiesConfiguration {
    }
}
