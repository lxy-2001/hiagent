package com.agentflow.web.config;

import com.agentflow.llm.AgentFlowProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigCredentialTest {

    @Test
    void rejectsMissingJwtSecret() {
        AgentFlowProperties properties = new AgentFlowProperties();

        assertThatThrownBy(() -> new SecurityConfig().jwtSecretKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentflow.security.jwt.secret");
    }

    @Test
    void rejectsJwtSecretShorterThanThirtyTwoBytes() {
        AgentFlowProperties properties = new AgentFlowProperties();
        properties.security().jwt().setSecret("too-short");

        assertThatThrownBy(() -> new SecurityConfig().jwtSecretKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void acceptsExplicitJwtSecretWithAtLeastThirtyTwoBytes() {
        AgentFlowProperties properties = new AgentFlowProperties();
        properties.security().jwt().setSecret("test-only-jwt-secret-which-is-long-enough-32");

        assertThat(new SecurityConfig().jwtSecretKey(properties)).isNotNull();
    }
}
