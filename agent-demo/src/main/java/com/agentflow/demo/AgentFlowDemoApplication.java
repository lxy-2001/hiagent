package com.agentflow.demo;

import com.agentflow.web.config.SecurityConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(SecurityConfig.class)
public class AgentFlowDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentFlowDemoApplication.class, args);
    }
}
