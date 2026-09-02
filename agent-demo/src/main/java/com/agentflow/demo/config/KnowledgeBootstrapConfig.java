package com.agentflow.demo.config;

import com.agentflow.demo.knowledge.KnowledgeService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
        name = "agentflow.knowledge.bootstrap.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KnowledgeBootstrapConfig {

    @Bean
    CommandLineRunner loadBuiltInKnowledge(KnowledgeService knowledgeService) {
        return args -> knowledgeService.reloadBuiltInKnowledge();
    }
}
