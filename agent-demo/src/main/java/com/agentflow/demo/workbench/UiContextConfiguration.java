package com.agentflow.demo.workbench;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Display facts only: enabling an adapter is not a connectivity check. */
@Configuration(proxyBeanMethods = false)
public class UiContextConfiguration {
    public enum Mode { REAL_CONFIGURED, OFFLINE_FIXTURE }
    public record UiContext(Mode mode, String modelLabel, boolean ragEnabled, boolean mcpEnabled) { }

    @Bean
    @ConditionalOnMissingBean(UiContext.class)
    UiContext workbenchUiContext(Environment environment) {
        return new UiContext(Mode.REAL_CONFIGURED, safeLabel(environment.getProperty("agentflow.model.chat-model")),
                environment.getProperty("agentflow.rag.enabled", Boolean.class, false),
                environment.getProperty("agentflow.mcp.enabled", Boolean.class, false));
    }

    static String safeLabel(String value) {
        if (value == null || value.isBlank() || value.startsWith("sk-")
                || !value.matches("[\\p{L}\\p{N}_.:/-]+")) return null;
        return value.substring(0, Math.min(value.length(), 128));
    }
}
