package com.agentflow.tool;

import com.agentflow.core.tool.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ToolPolicyProperties.class)
public class ToolPolicyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ToolExecutionPolicy.class)
    ToolExecutionPolicy toolExecutionPolicy(ToolPolicyProperties properties) {
        var rules=new java.util.LinkedHashMap<String,ToolPolicyDecision>();
        for (var rule:properties.rules()) {
            if (rule.toolName()==null || rule.toolName().isBlank() || rule.toolName().length()>100)
                throw new IllegalArgumentException("invalid policy tool name");
            var visible=rule.visibleArguments()==null?java.util.Set.<String>of():rule.visibleArguments();
            for (String name:visible) {
                String compact=name.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
                if (java.util.Set.of("password","token","secret","apikey","authorization").contains(compact))
                    throw new IllegalArgumentException("credential preview prohibited");
            }
            var decision=new ToolPolicyDecision(rule.action(),rule.risk(),rule.effect(),rule.actionSummary(),visible);
            if (rules.putIfAbsent(rule.toolName(),decision)!=null)
                throw new IllegalArgumentException("duplicate policy tool name");
        }
        return ToolExecutionPolicy.rules(rules);
    }
}
