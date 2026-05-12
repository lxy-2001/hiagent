package com.agentflow.demo.config;

import com.agentflow.demo.knowledge.KnowledgeService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeBootstrapConfig {

    @Bean
    CommandLineRunner loadBuiltInKnowledge(KnowledgeService knowledgeService) {
        return args -> knowledgeService.reloadBuiltInKnowledge();
    }
}
