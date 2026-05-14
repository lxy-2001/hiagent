package com.agentflow.rag;

import com.agentflow.core.rag.RagRetriever;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class AgentRagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RagRetriever ragRetriever() {
        return new NoopRagRetriever();
    }
}
